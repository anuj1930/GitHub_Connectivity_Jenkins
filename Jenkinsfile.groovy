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
        GIT_BRANCH_NAME = 'main'
        GIT_REPO_URL   = 'https://github.com/anuj1930/GitHub_Connectivity_Jenkins.git'
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
                          # Check output, not exit code
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

        stage('Detect README changes (persistent)') {
            steps {
                script {
                    // 1) List README files tracked by git (root + any subfolder)
                    String listCmdUnix = "git ls-files -- ':(glob)README*' ':(glob)**/README*' || true"
                    String listCmdWin  = "@echo off\r\ngit ls-files -- \":(glob)README*\" \":(glob)**/README*\" || exit /b 0"

                    def filesOut = isUnix()
                        ? sh(returnStdout: true, script: listCmdUnix).trim()
                        : bat(returnStdout: true, script: listCmdWin).trim()

                    List<String> readmeFiles = filesOut ? filesOut.split('\\r?\\n') as List<String> : []
                    echo "README files found: ${readmeFiles}"

                    // 2) Compute stable signature of paths+contents (SHA-256)
                    import java.security.MessageDigest
                    def md = MessageDigest.getInstance('SHA-256')
                    readmeFiles.sort().each { path ->
                        if (fileExists(path)) {
                            String content = readFile(file: path)
                            md.update(path.getBytes('UTF-8'))
                            md.update((byte)0)
                            md.update(content.getBytes('UTF-8'))
                            md.update((byte)0)
                        }
                    }
                    String currSig = md.digest().collect { String.format('%02x', it) }.join()

                    // 3) Load previous signature (if any) and last processed HEAD
                    String prevSig  = fileExists('.readme.sig') ? readFile('.readme.sig').trim() : ''
                    String prevHead = fileExists('.last_head')  ? readFile('.last_head').trim()  : ''
                    String head     = isUnix()
                        ? sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
                        : bat(returnStdout: true, script: """@echo off
                            for /f "delims=" %%A in ('git rev-parse HEAD') do @echo %%A
                          """).trim()

                    boolean hasReadme = !readmeFiles.isEmpty()
                    boolean readmeChanged = hasReadme && (currSig != prevSig)

                    echo "Prev README signature: ${prevSig ?: '(none)'}"
                    echo "Curr README signature: ${currSig}"
                    echo "Prev HEAD: ${prevHead ?: '(none)'}"
                    echo "Curr HEAD: ${head}"
                    echo "README changed (by signature)? ${readmeChanged}"

                    // 4) Export decision and persist current state for next build
                    env.README_CHANGED = readmeChanged ? 'true' : 'false'
                    writeFile file: '.readme.sig', text: currSig + '\n'
                    writeFile file: '.last_head',  text: head    + '\n'
                }
            }
        }

        stage('Show README diff') {
            when { environment name: 'README_CHANGED', value: 'true' }
            steps {
                script {
                    String base = fileExists('.last_head') ? readFile('.last_head').trim() : ''
                    String head = isUnix()
                        ? sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
                        : bat(returnStdout: true, script: """@echo off
                            for /f "delims=" %%A in ('git rev-parse HEAD') do @echo %%A
                          """).trim()

                    // Use previous HEAD as base if available and different; else fallback
                    if (!base?.trim() || base == head) {
                        if (isUnix()) {
                            base = sh(returnStdout: true,
                                      script: '(git rev-parse HEAD^ 2>/dev/null) || (git rev-list --max-parents=0 HEAD | head -1)'
                            ).trim()
                        } else {
                            base = bat(returnStdout: true, script: """@echo off
                            for /f "delims=" %%A in ('git rev-parse HEAD^ 2^>nul') do @echo %%A
                            """).trim()
                            if (!base?.trim()) {
                                base = bat(returnStdout: true, script: """@echo off
                                for /f "delims=" %%A in ('git rev-list --max-parents=0 HEAD') do @echo %%A
                                """).trim()
                            }
                        }
                    }

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
                        git --no-pager diff -U0 ${base}...${head} -- ":(glob)README*" ":(glob)**/README*" | findstr /R "^+" | findstr /V "++" > readme.added 2>nul || type NUL > readme.added
                        """
                    }

                    archiveArtifacts artifacts: 'readme.*', fingerprint: true, allowEmptyArchive: true
                }
            }
        }

        // -------------------- TEST ONLY when README changed --------------------
        stage('Test') {
            when { environment name: 'README_CHANGED', value: 'true' }
            steps {
                echo 'Running tests (README changed)…'
                script {
                    boolean win = !isUnix()
                    boolean hasWrapper = fileExists('mvnw') || fileExists('mvnw.cmd')
                    String mvnCmd = hasWrapper ? (win ? 'mvnw.cmd' : './mvnw') : (win ? 'mvn.cmd' : 'mvn')

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
            when { not { environment name: 'README_CHANGED', value: 'true' } }
            steps {
                echo 'No README changes detected; skipping Test stage.'
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
