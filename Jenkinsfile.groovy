pipeline {
    agent any

    options {
        timestamps()                 // requires Timestamper plugin
        ansiColor('xterm')           // requires AnsiColor plugin
        skipDefaultCheckout(true)
        buildDiscarder(logRotator(numToKeepStr: '30'))
    }

    triggers {
        pollSCM('H/5 * * * *')       // every ~5 minutes
    }

    environment {
        README_CHANGED = 'false'
        GIT_BRANCH_NAME = 'main'
        GIT_REPO_URL   = 'https://github.com/anuj1930/GitHub_Connectivity_Jenkins.git'
        MAVEN_REPO     = "${WORKSPACE}/.m2/repository"  // local Maven cache for speed
    }

    stages {
        stage('Checkout (from repo in code)') {
            steps {
                git(
                    url: env.GIT_REPO_URL,
                    branch: env.GIT_BRANCH_NAME,
                    changelog: true,
                    poll: true
                // credentialsId: 'github-credentials' // <-- uncomment if private repo
                )

                // Show latest commit (cross-platform)
                script {
                    if (isUnix()) {
                        sh 'git --no-pager log -1 --oneline || true'
                    } else {
                        bat '''@echo off
                        git --no-pager log -1 --oneline || exit /b 0
                        '''
                    }
                }
            }
        }

        stage('Detect README changes') {
            steps {
                script {
                    // 1) Prefer Jenkins changeSets (when build triggered by SCM)
                    def changedFiles = []
                    for (cs in currentBuild.changeSets) {
                        for (entry in cs.items) {
                            for (file in entry.affectedFiles) {
                                changedFiles << file.path
                            }
                        }
                    }

                    // 2) Fallback: compute changed files via git diff (first/manual runs)
                    if (changedFiles.isEmpty()) {
                        echo 'No changeSets found; falling back to git diff to detect changed files.'

                        def from = (env.GIT_PREVIOUS_SUCCESSFUL_COMMIT ?: env.GIT_PREVIOUS_COMMIT)
                        def to

                        if (isUnix()) {
                            to = sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
                            if (!from?.trim()) {
                                from = sh(returnStdout: true, script: "git rev-parse HEAD~1 || echo ''").trim()
                            }
                        } else {
                            to = bat(returnStdout: true, script: """@echo off
                            for /f "delims=" %%A in ('git rev-parse HEAD') do @echo %%A
                            """).trim()

                            if (!from?.trim()) {
                                // If first commit, HEAD~1 may fail; produce empty output instead of failing
                                from = bat(returnStdout: true, script: """@echo off
                                for /f "delims=" %%A in ('git rev-parse HEAD^~1 2^>nul') do @echo %%A
                                """).trim()
                            }
                        }

                        if (from?.trim()) {
                            if (isUnix()) {
                                def out = sh(returnStdout: true,
                                             script: "git diff --name-only ${from} ${to} || true").trim()
                                if (out) changedFiles = out.split('\\r?\\n') as List<String>
                            } else {
                                def out = bat(returnStdout: true, script: """@echo off
                                git diff --name-only ${from} ${to} || exit /b 0
                                """).trim()
                                if (out) changedFiles = out.split('\\r?\\n') as List<String>
                            }
                        } else {
                            echo 'Could not determine previous commit (first build or shallow history).'
                        }
                    }

                    echo "Changed files (raw): ${changedFiles}"

                    // --- Robust cross-platform detection ---
                    // Normalize: trim whitespace, convert backslashes, lowercase
                    def normalized = changedFiles.collect { p ->
                        (p ?: '')
                            .trim()
                            .replace('\\', '/')
                            .toLowerCase()
                    }
                    echo "Changed files (normalized): ${normalized}"

                    // Match README variants anywhere in the tree
                    def readmeChanged = normalized.any { f ->
                        f == 'readme' ||
                        f == 'readme.md' ||
                        f.endsWith('/readme') ||
                        f.endsWith('/readme.md')
                    }

                    env.README_CHANGED = readmeChanged ? 'true' : 'false'
                    echo "README changed? ${env.README_CHANGED}"
                }
            }
        }

        stage('Build') {
            when { environment name: 'README_CHANGED', value: 'true' }
            steps {
                echo 'Building the project...'
                script {
                    boolean win = !isUnix()
                    boolean hasWrapper = fileExists('mvnw') || fileExists('mvnw.cmd')
                    String mvnCmd = hasWrapper ? (win ? 'mvnw.cmd' : './mvnw')
                                               : (win ? 'mvn.cmd'  : 'mvn')

                    // Print versions (don’t fail pipeline if mvn -v errors)
                    if (win) {
                        bat "\"${mvnCmd}\" -v || exit /b 0"
                    } else {
                        sh "\"${mvnCmd}\" -v || true"
                    }

                    // Build + unit tests (verify) with a workspace-local repo cache
                    def line = "\"${mvnCmd}\" -B -U -Dmaven.repo.local=${MAVEN_REPO} clean verify"
                    if (win) { bat line } else { sh line }
                }
            }
        }

        stage('Test') {
            when { environment name: 'README_CHANGED', value: 'true' }
            steps {
                echo 'Running tests...'
                script {
                    boolean win = !isUnix()
                    boolean hasWrapper = fileExists('mvnw') || fileExists('mvnw.cmd')
                    String mvnCmd = hasWrapper ? (win ? 'mvnw.cmd' : './mvnw')
                                               : (win ? 'mvn.cmd'  : 'mvn')

                    def line = "\"${mvnCmd}\" -B -Dmaven.repo.local=${MAVEN_REPO} test"
                    if (win) { bat line } else { sh line }
                }
            }
            post {
                always {
                    junit '**/target/surefire-reports/*.xml'
                }
            }
        }

        stage('No README change — Skip Build/Test') {
            when { not { environment name: 'README_CHANGED', value: 'true' } }
            steps {
                echo 'No README changes detected; skipping Build/Test stages.'
            }
        }

        stage('Build') {
            when { changeset '**/README*' }   // matches README and README.* anywhere
            steps { /* build steps */ }
        }

        stage('Test') {
            when { changeset '**/README*' }
            steps { /* test steps */ }
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
