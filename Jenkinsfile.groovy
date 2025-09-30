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
        stage('Checkout') {
            steps {
                checkout scm
                sh '''
                  set -e
                  git config --global --add safe.directory "$PWD"

                  # Ensure we have enough history for reliable diffs (handle shallow clones)
                  if git rev-parse --is-shallow-repository >/dev/null 2>&1; then
                    git fetch --unshallow --tags || true
                  else
                    git fetch --all --tags --prune
                  fi
                '''
            }
        }

        stage('Show README diff') {
            // Only run this stage when README changed in this build
            when {
                anyOf {
                    changeset pattern: 'README.md',       caseSensitive: false
                    changeset pattern: 'README.*',        caseSensitive: false
                    changeset pattern: '**/README.md',    caseSensitive: false
                    changeset pattern: '**/README.*',     caseSensitive: false
                }
            }
            steps {
                script {
                    // Resolve HEAD and a suitable BASE commit for the diff
                    def head = sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
                    def base = env.GIT_PREVIOUS_SUCCESSFUL_COMMIT ?: env.GIT_PREVIOUS_COMMIT

                    if (!base?.trim()) {
                        // Fallback: previous commit if available, else the root commit
                        base = sh(
                          returnStdout: true,
                          script: '(git rev-parse HEAD^ 2>/dev/null) || (git rev-list --max-parents=0 HEAD | tail -1)'
                        ).trim()
                    }

                    echo "Base commit: ${base}"
                    echo "Head commit: ${head}"

                    // Produce the diff (only changed lines, 0 context), plus stats and added-lines view
                    sh """
                        set -e
                        echo "────────────────────────────────────────"
                        echo "Changed lines in README (unified=0):"
                        echo "────────────────────────────────────────"
                        git --no-pager diff --no-color -U0 ${base}...${head} -- README* '**/README*' | tee readme.diff || true

                        echo ""
                        echo "────────────────────────────────────────"
                        echo "Summary (added   removed   filename):"
                        echo "────────────────────────────────────────"
                        git --no-pager diff --no-color --numstat ${base}...${head} -- README* '**/README*' | tee readme.numstat || true

                        echo ""
                        echo "────────────────────────────────────────"
                        echo "Only added lines:"
                        echo "────────────────────────────────────────"
                        git --no-pager diff -U0 ${base}...${head} -- README* '**/README*' | sed -n 's/^+[^+]/&/p' | sed 's/^+//' | tee readme.added || true
                    """

                    archiveArtifacts artifacts: 'readme.*', fingerprint: true, allowEmptyArchive: true
                }
            }
        }

        stage('Result summary') {
            when {
                anyOf {
                    changeset pattern: 'README.md',       caseSensitive: false
                    changeset pattern: 'README.*',        caseSensitive: false
                    changeset pattern: '**/README.md',    caseSensitive: false
                    changeset pattern: '**/README.*',     caseSensitive: false
                }
            }
            steps {
                script {
                    def stats = sh(returnStdout: true, script: 'cat readme.numstat 2>/dev/null || true').trim()
                    if (stats) {
                        // numstat format: "<added> <deleted> <path>"
                        // If multiple README files changed, there will be multiple lines; sum them.
                        def totalAdded = 0
                        def totalDeleted = 0
                        stats.split('\\r?\\n').each { line ->
                            def parts = line.trim().split('\\s+')
                            if (parts.size() >= 3) {
                                // Handle "-" which appears on binary changes (shouldn't happen for README)
                                def a = (parts[0] == '-' ? '0' : parts[0]) as int
                                def d = (parts[1] == '-' ? '0' : parts[1]) as int
                                totalAdded += a
                                totalDeleted += d
                            }
                        }
                        echo "README update result: +${totalAdded} / -${totalDeleted} lines across ${stats.split('\\r?\\n').size()} file(s)."
                        currentBuild.description = "README +${totalAdded}/-${totalDeleted}"
                    } else {
                        echo 'No numstat produced; possibly no effective change to README content.'
                    }
                }
            }
        }

        // Optional: Stage that only runs when README did NOT change (for clear feedback)
        stage('No README change') {
            when {
                not {
                    anyOf {
                        changeset pattern: 'README.md',       caseSensitive: false
                        changeset pattern: 'README.*',        caseSensitive: false
                        changeset pattern: '**/README.md',    caseSensitive: false
                        changeset pattern: '**/README.*',     caseSensitive: false
                    }
                }
            }
            steps {
                echo 'No README changes detected in this revision. Nothing to report.'
            }
        }
    }

    post {
        success {
            echo 'Pipeline finished successfully.'
        }
        notBuilt {
            echo 'Build marked NOT_BUILT.'
        }
        failure {
            echo 'Pipeline failed. Check the logs above.'
        }
        always {
            echo "Build URL: ${env.BUILD_URL}"
        }
    }
}
