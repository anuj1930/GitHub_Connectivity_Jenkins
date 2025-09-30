pipeline {
    agent any

    options {
        timestamps()
        ansiColor('xterm')
        skipDefaultCheckout(true)
        buildDiscarder(logRotator(numToKeepStr: '30'))
    }

    triggers {
        // Poll every ~5 minutes (use webhook if available)
        pollSCM('H/5 * * * *')
    }

    environment {
        README_CHANGED = 'false'
        GIT_BRANCH_NAME = 'main'
        GIT_REPO_URL   = 'https://github.com/anuj1930/GitHub_Connectivity_Jenkins.git'
        MAVEN_REPO     = "${WORKSPACE}/.m2/repository"
    }

    stages {
        stage('Checkout (from repo in code)') {
            steps {
                checkout scm
                script {
                    if (isUnix()) {
                        sh 'git --no-pager log -1 --oneline || true'
                        // Ensure enough history for reliable diffs (first build or shallow clones)
                        sh '''
                          set -e
                          git config --global --add safe.directory "$PWD"
                          if git rev-parse --is-shallow-repository >/dev/null 2>&1; then
                            git fetch --unshallow --tags || true
                          else
                            git fetch --all --tags --prune || true
                          fi
                        '''
                    } else {
                        bat '''@echo off
                        git --no-pager log -1 --oneline || exit /b 0
                        '''
                        // On Windows you may want to ensure full history too (if needed)
                        bat '''@echo off
                        git config --global --add safe.directory "%CD%" 2>nul
                        git rev-parse --is-shallow-repository >nul 2>&1
                        if %ERRORLEVEL%==0 (
                          git fetch --unshallow --tags || exit /b 0
                        ) else (
                          git fetch --all --tags --prune || exit /b 0
                        )
                        '''
                    }
                }
            }
        }

        stage('Detect README changes') {
            steps {
                script {
                    def changedFiles = []
                    // 1) First, try Jenkins changeSets
                    for (cs in currentBuild.changeSets) {
                        for (entry in cs.items) {
                            for (file in entry.affectedFiles) {
                                changedFiles << file.path
                            }
                        }
                    }

                    // 2) If empty, fall back to raw git diff name-only
                    if (changedFiles.isEmpty()) {
                        echo 'No changeSets found; falling back to git diff to detect changed files.'
                        def from = (env.GIT_PREVIOUS_SUCCESSFUL_COMMIT ?: env.GIT_PREVIOUS_COMMIT)
                        def to

                        if (isUnix()) {
                            to = sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
                            if (!from?.trim()) {
                                // previous commit, else root
                                from = sh(returnStdout: true,
                                    script: '(git rev-parse HEAD~1 2>/dev/null) || (git rev-list --max-parents=0 HEAD | tail -1)').trim()
                            }
                            if (from?.trim()) {
                                def out = sh(returnStdout: true,
                                             script: "git diff --name-only ${from} ${to} || true").trim()
                                if (out) changedFiles = out.split('\\r?\\n') as List<String>
                            } else {
                                echo 'Could not determine previous commit (first build or shallow history).'
                            }
                        } else {
                            to = bat(returnStdout: true, script: """@echo off
                            for /f "delims=" %%A in ('git rev-parse HEAD') do @echo %%A
                            """).trim()

                            if (!from?.trim()) {
                                // previous commit, else root
                                from = bat(returnStdout: true, script: """@echo off
                                for /f "delims=" %%A in ('git rev-list --max-parents=0 HEAD ^| tail -1') do @echo %%A
                                """).trim()
                                if (!from?.trim()) {
                                    from = bat(returnStdout: true, script: """@echo off
                                    for /f "delims=" %%A in ('git rev-parse HEAD~1 2^>nul') do @echo %%A
                                    """).trim()
                                }
                            }
                            if (from?.trim()) {
                                def out = bat(returnStdout: true, script: """@echo off
                                git diff --name-only ${from} ${to} || exit /b 0
                                """).trim()
                                if (out) changedFiles = out.split('\\r?\\n') as List<String>
                            } else {
                                echo 'Could not determine previous commit (first build or shallow history).'
                            }
                        }
                    }

                    echo "Changed files (raw): ${changedFiles}"

                    def normalized = changedFiles.collect { p ->
                        (p ?: '')
                            .trim()
                            .replace('\\', '/')
                            .toLowerCase()
                    }
                    echo "Changed files (normalized): ${normalized}"

                    // Match README files at root or any folder, with or without extension
                    def readmeChanged = normalized.any { f ->
                        f == 'readme' ||
                        f == 'readme.md' ||
                        f.startsWith('readme.') ||
                        f.endsWith('/readme') ||
                        f.endsWith('/readme.md') ||
                        f.contains('/readme.')
                    }

                    env.README_CHANGED = readmeChanged ? 'true' : 'false'
                    echo "README changed? ${env.README_CHANGED}"
                }
            }
        }

        // ---- NEW: Show exactly what changed in README and archive artifacts ----
        stage('Show README diff') {
            when { environment name: 'README_CHANGED', value: 'true' }
            steps {
                script {
                    // Find base and head for diff
                    String head
                    String base = env.GIT_PREVIOUS_SUCCESSFUL_COMMIT ?: env.GIT_PREVIOUS_COMMIT

                    if (isUnix()) {
                        head = sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
                        if (!base?.trim()) {
                            base = sh(
                                returnStdout: true,
                                script: '(git rev-parse HEAD^ 2>/dev/null) || (git rev-list --max-parents=0 HEAD | tail -1)'
                            ).trim()
                        }

                        echo "Base commit: ${base}"
                        echo "Head commit: ${head}"

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
                        head = bat(returnStdout: true, script: """@echo off
                        for /f "delims=" %%A in ('git rev-parse HEAD') do @echo %%A
                        """).trim()
                        if (!base?.trim()) {
                            // try previous commit, else root
                            base = bat(returnStdout: true, script: """@echo off
                            for /f "delims=" %%A in ('git rev-parse HEAD^ 2^>nul') do @echo %%A
                            """).trim()
                            if (!base?.trim()) {
                                base = bat(returnStdout: true, script: """@echo off
                                for /f "delims=" %%A in ('git rev-list --max-parents=0 HEAD ^| tail -1') do @echo %%A
                                """).trim()
                            }
                        }

                        echo "Base commit: ${base}"
                        echo "Head commit: ${head}"

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

                    // Show Maven version but don't fail the build if Maven not present
                    if (win) {
                        bat "\"${mvnCmd}\" -v || exit /b 0"
                    } else {
                        sh "\"${mvnCmd}\" -v || true"
                    }

                    // mvn test includes compile phases for main + test code
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
