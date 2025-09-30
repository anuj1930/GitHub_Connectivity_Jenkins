pipeline {
    agent any

    options {
        timestamps()
        ansiColor('xterm')
        skipDefaultCheckout(true)
        buildDiscarder(logRotator(numToKeepStr: '30'))
    }

    triggers {
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
                    def changedFiles = []
                    for (cs in currentBuild.changeSets) {
                        for (entry in cs.items) {
                            for (file in entry.affectedFiles) {
                                changedFiles << file.path
                            }
                        }
                    }

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

                    def normalized = changedFiles.collect { p ->
                        (p ?: '')
                            .trim()
                            .replace('\\', '/')
                            .toLowerCase()
                    }
                    echo "Changed files (normalized): ${normalized}"

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
                    String mvnCmd = hasWrapper ? (win ? 'mvnw.cmd' : './mvnw') : (win ? 'mvn.cmd' : 'mvn')

                    if (win) {
                        bat "\"${mvnCmd}\" -v || exit /b 0"
                    } else {
                        sh "\"${mvnCmd}\" -v || true"
                    }

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
                    String mvnCmd = hasWrapper ? (win ? 'mvnw.cmd' : './mvnw') : (win ? 'mvn.cmd' : 'mvn')

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
