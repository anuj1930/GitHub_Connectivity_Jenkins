pipeline {
    agent any

    options {
        timestamps()
        ansiColor('xterm')
        // Avoid the implicit checkout so we can do it explicitly with `git` step
        skipDefaultCheckout(true)
        buildDiscarder(logRotator(numToKeepStr: '30'))
    }

    // In-code polling trigger (works best when Jenkins can resolve SCM from the job or the `git` step)
    triggers {
        pollSCM('H/5 * * * *')  // every ~5 minutes
    }

    environment {
        // Flag we’ll set after detecting changes
        README_CHANGED = 'false'
        GIT_BRANCH_NAME = 'main'
        GIT_REPO_URL   = 'https://github.com/anuj1930/GitHub_Connectivity_Jenkins.git'
    }

    stages {

        stage('Checkout (from repo in code)') {
            steps {
                // Checkout using explicit repo & branch in code
                // changelog & poll help Jenkins track changes/polling with Git plugin
                git(
                    url: env.GIT_REPO_URL,
                    branch: env.GIT_BRANCH_NAME,
                    changelog: true,
                    poll: true
                    // credentialsId: 'github-credentials' // <-- uncomment if private repo
                )

                // Show the latest commit for visibility
                sh 'git --no-pager log -1 --oneline || true'
            }
        }

        stage('Detect README changes') {
            steps {
                script {
                    // 1) Try to use Jenkins changeSets (best when job triggered by SCM)
                    def changedFiles = []
                    for (cs in currentBuild.changeSets) {
                        for (entry in cs.items) {
                            for (file in entry.affectedFiles) {
                                changedFiles << file.path
                            }
                        }
                    }

                    // 2) Fallback to git diff range if changeSets are empty (manual/first build)
                    if (changedFiles.isEmpty()) {
                        echo "No changeSets found; falling back to git diff to detect changed files."

                        // Try previous successful or previous commit provided by Jenkins
                        def from = (env.GIT_PREVIOUS_SUCCESSFUL_COMMIT ?: env.GIT_PREVIOUS_COMMIT)
                        def to = sh(returnStdout: true, script: "git rev-parse HEAD").trim()

                        // If we still can't get a 'from', try previous commit on the same branch
                        if (!from?.trim()) {
                            from = sh(returnStdout: true, script: "git rev-parse HEAD~1 || echo ''").trim()
                        }

                        if (from?.trim()) {
                            def diffOut = sh(returnStdout: true, script: "git diff --name-only ${from} ${to} || true").trim()
                            if (diffOut) {
                                changedFiles = diffOut.split('\\n') as List<String>
                            }
                        } else {
                            echo "Could not determine previous commit (first build or shallow history)."
                        }
                    }

                    echo "Changed files: ${changedFiles}"

                    // Case-insensitive match for README variants in any folder
                    // Matches: README, README.md, docs/README, docs/README.MD, etc.
                    def readmeChanged = changedFiles.any { it ==~ /(?i)(^|.*\/)README(\.[^\/]+)?$/ }
                    env.README_CHANGED = readmeChanged ? 'true' : 'false'
                    echo "README changed? ${env.README_CHANGED}"
                }
            }
        }

        stage('Build') {
            when {
                environment name: 'README_CHANGED', value: 'true'
            }
            steps {
                echo 'Building the project...'
                sh 'mvn -v || true'
                sh 'mvn clean install'
            }
        }

        stage('Test') {
            when {
                environment name: 'README_CHANGED', value: 'true'
            }
            steps {
                echo 'Running tests...'
                sh 'mvn test'
            }
            post {
                always {
                    junit '**/target/surefire-reports/*.xml'
                }
            }
        }

        stage('No README change — Skip Build/Test') {
            when {
                not { environment name: 'README_CHANGED', value: 'true' }
            }
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