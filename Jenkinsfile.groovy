pipeline {
    agent any

    options {
        timestamps()
        ansiColor('xterm')
        skipDefaultCheckout(true)
        buildDiscarder(logRotator(numToKeepStr: '30'))
    }

    triggers {
        // Poll every ~5 minutes (prefer GitHub webhook if available)
        pollSCM('H/5 * * * *')
    }

    environment {
        README_CHANGED = 'false'
        DIFF_BASE      = ''
        DIFF_HEAD      = ''
        MAVEN_REPO     = "${WORKSPACE}/.m2/repository"
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
                script {
                    if (isUnix()) {
                        sh '''
                          set -e
                          git --no-pager log -1 --oneline || true
                          git config --global --add safe.directory "$PWD"
                          # Correct shallow detection: check output, not exit code
                          if [ "$(git rev-parse --is-shallow-repository)" = "true" ]; then
                            git fetch --unshallow --tags || true
                          else
                            git fetch --all --tags --prune || true
                          fi
                        '''
                    } else {
                        bat '''@echo off
                        git --no-pager log -1 --oneline || exit /b 0
                        git config --global --add safe.directory "%CD%" 2>nul
                        for /f "delims=" %%S in ('git rev-parse --is-shallow-repository') do set SHALLOW=%%S
                        if /I "%SHALLOW%"=="true" (
                          git fetch --unshallow --tags || exit /b 0
                        ) else (
                          git fetch --all --tags --prune || exit /b 0
                        )
                        '''
                    }
                }
            }
        }

        stage('Detect README changes (persist previous head)') {
            steps {
                script {
                    // HEAD now
                    String head = isUnix()
                        ? sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
                        : bat(returnStdout: true, script: '@echo off\r\nfor /f "delims=" %%A in (\'git rev-parse HEAD\') do @echo %%A').trim()

                    // Prefer previously stored .last_head as base
                    String base = fileExists('.last_head') ? readFile('.last_head').trim() : ''

                    // If not available, fall back to Jenkins envs, else previous commit or root
                    if (!base?.trim()) {
                        base = env.GIT_PREVIOUS_SUCCESSFUL_COMMIT ?: env.GIT_PREVIOUS_COMMIT ?: ''
                    }
                    if (!base?.trim()) {
                        if (isUnix()) {
                            base = sh(
                                returnStdout: true,
                                script: '(git rev-parse HEAD^ 2>/dev/null) || (git rev-list --max-parents=0 HEAD | head -1)'
                            ).trim()
                        } else {
                            base = bat(returnStdout: true, script: '@echo off\r\nfor /f "delims=" %%A in (\'git rev-parse HEAD^ 2^>nul\') do @echo %%A').trim()
                            if (!base?.trim()) {
                                base = bat(returnStdout: true, script: '@echo off\r\nfor /f "delims=" %%A in (\'git rev-list --max-parents=0 HEAD\') do @echo %%A').trim()
                            }
                        }
                    }

                    // If base equals head, attempt previous commit as base
                    if (base == head) {
                        if (isUnix()) {
                            String alt = sh(returnStdout: true, script: 'git rev-parse HEAD^ 2>/dev/null || true').trim()
                            if (alt) base = alt
                        } else {
                            String alt = bat(returnStdout: true, script: '@echo off\r\nfor /f "delims=" %%A in (\'git rev-parse HEAD^ 2^>nul\') do @echo %%A').trim()
                            if (alt) base = alt
                        }
                    }

                    echo "Base commit for detection: ${base}"
                    echo "Head commit for detection: ${head}"

                    // Which README files changed between base...head?
                    String changedList = isUnix()
                        ? sh(returnStdout: true,
                              script: "git diff --name-only ${base}...${head} -- ':(glob)README*' ':(glob)**/README*' || true"
                          ).trim()
                        : bat(returnStdout: true, script:
                              '@echo off\r\n' +
                              'git diff --name-only ' + base + '...' + head + ' -- ":(glob)README*" ":(glob)**/README*" || exit /b 0'
                          ).trim()

                    List<String> changedReadmes = changedList ? changedList.split('\\r?\\n') as List<String> : []
                    echo "Changed README files: ${changedReadmes}"

                    // ✅ Sandbox-safe env assignments (no bracket syntax)
                    env.README_CHANGED = (changedReadmes && changedReadmes.size() > 0) ? 'true' : 'false'
                    env.DIFF_BASE = base
                    env.DIFF_HEAD = head

                    echo "README_CHANGED=${env.README_CHANGED}"
                }
            }
        }

        stage('Show README diff') {
            // ✅ Use expression to read the runtime env value
            when { expression { env.README_CHANGED?.trim() == 'true' } }
            steps {
                script {
                    String base = env.DIFF_BASE
                    String head = env.DIFF_HEAD

                    echo "Diff base: ${base}"
                    echo "Diff head: ${head}"

                    if (isUnix()) {
                        sh """
                          set -e
                          echo "────────────────────────────────────────"
                          echo "Changed lines in README (unified=0):"
                          echo "────────────────────────────────────────"
                          git --no-pager diff --no-color -U0 ${base}...${head} -- ':(glob)README*' ':(glob)**/README*' | tee readme.diff || true

                          echo ""
                          echo "────────────────────────────────────────"
                          echo "Summary (added   removed   filename):"
                          echo "────────────────────────────────────────"
                          git --no-pager diff --no-color --numstat ${base}...${head} -- ':(glob)README*' ':(glob)**/README*' | tee readme.numstat || true

                          echo ""
                          echo "────────────────────────────────────────"
                          echo "Only added lines:"
                          echo "────────────────────────────────────────"
                          git --no-pager diff -U0 ${base}...${head} -- ':(glob)README*' ':(glob)**/README*' \
                            | sed -n 's/^+[^+]/&/p' | sed 's/^+//' | tee readme.added || true

                          # Only removed lines (exclude diff headers)
                          git --no-pager diff -U0 ${base}...${head} -- ':(glob)README*' ':(glob)**/README*' \
                            | sed -n 's/^-[^-]/&/p' | sed 's/^-//' | tee readme.removed || true

                          # Word counts
                          ADDED_WORDS=\$(cat readme.added 2>/dev/null | tr -s '[:space:]' '\\n' | grep -c -E '\\S' || true)
                          REMOVED_WORDS=\$(cat readme.removed 2>/dev/null | tr -s '[:space:]' '\\n' | grep -c -E '\\S' || true)
                          printf "added_words=%s\\nremoved_words=%s\\n" "\$ADDED_WORDS" "\$REMOVED_WORDS" | tee readme.wordstats

                          echo ""
                          echo "────────────────────────────────────────"
                          echo "Word-level summary:"
                          echo "────────────────────────────────────────"
                          echo "Added words   : \$ADDED_WORDS"
                          echo "Removed words : \$REMOVED_WORDS"
                        """
                    } else {
                        bat """@echo off
                        echo ────────────────────────────────────────
                        echo Changed lines in README (unified=0):
                        echo ────────────────────────────────────────
                        git --no-pager diff --no-color -U0 ${base}...${head} -- ":(glob)README*" ":(glob)**/README*" > readme.diff 2>nul || type NUL > readme.diff

                        echo.
                        echo ────────────────────────────────────────
                        echo Summary (added   removed   filename):
                        echo ────────────────────────────────────────
                        git --no-pager diff --no-color --numstat ${base}...${head} -- ":(glob)README*" ":(glob)**/README*" > readme.numstat 2>nul || type NUL > readme.numstat

                        echo.
                        echo ────────────────────────────────────────
                        echo Only added lines:
                        echo ────────────────────────────────────────
                        git --no-pager diff -U0 ${base}...${head} -- ":(glob)README*" ":(glob)**/README*" | findstr /R "^[+]" | findstr /V "^[+][+]" > readme.added 2>nul || type NUL > readme.added

                        rem Only removed lines (exclude headers like --- a/file)
                        git --no-pager diff -U0 ${base}...${head} -- ":(glob)README*" ":(glob)**/README*" | findstr /R "^[-]" | findstr /V "^[-][-]" > readme.removed 2>nul || type NUL > readme.removed

                        rem Word counts via PowerShell
                        powershell -NoProfile -Command ^
                          "$a = (Test-Path 'readme.added')   ? (Get-Content 'readme.added'  -Raw) : ''; " ^
                          "$r = (Test-Path 'readme.removed') ? (Get-Content 'readme.removed' -Raw) : ''; " ^
                          "$aCount = ($a -split '\\s+') | Where-Object { \\$_ -ne '' } | Measure-Object | ForEach-Object Count; " ^
                          "$rCount = ($r -split '\\s+') | Where-Object { \\$_ -ne '' } | Measure-Object | ForEach-Object Count; " ^
                          "'added_words='  + $aCount + \"`n\" + 'removed_words=' + $rCount | Out-File 'readme.wordstats' -Encoding ascii; " ^
                          "Write-Host '────────────────────────────────────────'; " ^
                          "Write-Host 'Word-level summary:'; " ^
                          "Write-Host ('Added words   : ' + $aCount); " ^
                          "Write-Host ('Removed words : ' + $rCount); "
                        """
                    }

                    archiveArtifacts artifacts: 'readme.*', fingerprint: true, allowEmptyArchive: true
                }
            }
        }

        // -------------------- TEST ONLY when README changed --------------------
        stage('Test') {
            when { expression { env.README_CHANGED?.trim() == 'true' } }
            steps {
                echo 'Running tests (README changed)…'
                script {
                    boolean win = !isUnix()
                    boolean hasWrapper = fileExists('mvnw') || fileExists('mvnw.cmd')
                    String mvnCmd = hasWrapper ? (win ? 'mvnw.cmd' : './mvnw') : (win ? 'mvn.cmd' : 'mvn')

                    // Show Maven version but don’t fail if not present
                    if (win) {
                        bat "\"${mvnCmd}\" -v || exit /b 0"
                    } else {
                        sh "\"${mvnCmd}\" -v || true"
                    }

                    def line = "\"${mvnCmd}\" -B -U -Dmaven.repo.local=${MAVEN_REPO} test"
                    if (win) { bat line } else { sh line }
                }
            }
            post {
                always {
                    junit '**/target/surefire-reports/*.xml'
                }
            }
        }

        stage('No README change — Skip Test') {
            when { expression { env.README_CHANGED?.trim() != 'true' } }
            steps {
                echo 'No README changes detected; skipping Test stage.'
            }
        }

        // Persist the current head so the next run compares against it
        stage('Persist state for next run') {
            steps {
                script {
                    String head = isUnix()
                        ? sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
                        : bat(returnStdout: true, script: '@echo off\r\nfor /f "delims=" %%A in (\'git rev-parse HEAD\') do @echo %%A').trim()

                    writeFile file: '.last_head', text: head + '\n'
                    echo "Stored .last_head = ${head}"
                }
            }
        }
    }

    post {
        always {
            echo "Build finished with status: ${currentBuild.currentResult}"
        }
        success {
            echo '✅ Pipeline succeeded.'
        }
        failure {
            echo '❌ Pipeline failed — check Console Output.'
        }
    }
}
