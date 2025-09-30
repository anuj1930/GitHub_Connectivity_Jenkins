pipeline {
  agent any

  options {
    timestamps()
    ansiColor('xterm')
    skipDefaultCheckout(true)
    buildDiscarder(logRotator(numToKeepStr: '30'))
  }

  triggers {
    // Poll every 5 minutes (use a GitHub webhook if you can)
    pollSCM('H/5 * * * *')
  }

  environment {
    README_CHANGED = 'false'
    DIFF_BASE = ''
    DIFF_HEAD = ''
    MAVEN_REPO = "${WORKSPACE}/.m2/repository"
  }

  stages {

    // 1) Checkout + ensure enough history
    stage('Checkout') {
      steps {
        checkout scm
        script {
          if (isUnix()) {
            sh '''
              set -e
              git --no-pager log -1 --oneline || true
              git config --global --add safe.directory "$PWD"
              # Decide shallow vs full based on output (not exit code)
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

    // 2) Establish base and head (persisted base if available)
    stage('Locate base/head') {
      steps {
        script {
          String head = isUnix()
            ? sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
            : bat(returnStdout: true, script: '@echo off\r\nfor /f "delims=" %%A in (\'git rev-parse HEAD\') do @echo %%A').trim()

          String base = fileExists('.last_head') ? readFile('.last_head').trim() : ''
          if (!base?.trim()) {
            base = env.GIT_PREVIOUS_SUCCESSFUL_COMMIT ?: env.GIT_PREVIOUS_COMMIT ?: ''
          }
          if (!base?.trim()) {
            if (isUnix()) {
              base = sh(returnStdout: true, script: '(git rev-parse HEAD^ 2>/dev/null) || (git rev-list --max-parents=0 HEAD | head -1)').trim()
            } else {
              base = bat(returnStdout: true, script: '@echo off\r\nfor /f "delims=" %%A in (\'git rev-parse HEAD^ 2^>nul\') do @echo %%A').trim()
              if (!base?.trim()) {
                base = bat(returnStdout: true, script: '@echo off\r\nfor /f "delims=" %%A in (\'git rev-list --max-parents=0 HEAD\') do @echo %%A').trim()
              }
            }
          }

          // If base==head (e.g., first run after persisting), try one commit back
          if (base == head) {
            if (isUnix()) {
              def alt = sh(returnStdout: true, script: 'git rev-parse HEAD^ 2>/dev/null || true').trim()
              if (alt) base = alt
            } else {
              def alt = bat(returnStdout: true, script: '@echo off\r\nfor /f "delims=" %%A in (\'git rev-parse HEAD^ 2^>nul\') do @echo %%A').trim()
              if (alt) base = alt
            }
          }

          echo "Base commit: ${base}"
          echo "Head commit: ${head}"

          env.DIFF_BASE = base
          env.DIFF_HEAD = head
        }
      }
    }

    // 3) Diagnostics (we test both `..` and `...` and show what Git sees)
    stage('Diagnostics — changed files') {
      steps {
        script {
          String base = env.DIFF_BASE
          String head = env.DIFF_HEAD

          if (isUnix()) {
            sh """
              set -e
              echo "### name-only (two-dot) ${base}..${head}"
              git diff --name-only ${base}..${head} || true

              echo ""
              echo "### name-only (triple-dot) ${base}...${head}"
              git diff --name-only ${base}...${head} || true
            """
          } else {
            bat """@echo off
            echo ### name-only (two-dot) %DIFF_BASE%..%DIFF_HEAD%
            git diff --name-only %DIFF_BASE%..%DIFF_HEAD% || exit /b 0

            echo.
            echo ### name-only (triple-dot) %DIFF_BASE%...%DIFF_HEAD%
            git diff --name-only %DIFF_BASE%...%DIFF_HEAD% || exit /b 0
            """
          }
        }
      }
    }

    // 4) Detect README changes by filtering the name-only lists (no fragile return codes)
    stage('Detect README changes') {
      steps {
        script {
          String base = env.DIFF_BASE
          String head = env.DIFF_HEAD

          // Build a unified list of changed files from both two-dot and triple-dot ranges
          if (isUnix()) {
            sh """
              set -e
              git diff --name-only ${base}..${head} > .changed_2 || true
              git diff --name-only ${base}...${head} > .changed_3 || true
              cat .changed_2 .changed_3 | sort -u > .changed_all || true

              # Filter to README files (case-insensitive), keep normalized forward slashes
              cat .changed_all | sed 's/\\\\\\\\/\\//g' | awk '{print tolower(\$0)}' \\
                | awk '/(^|\\/)readme(\\..*)?$/ {print}' \\
                | sort -u > readme.changed.list || true
            """
          } else {
            bat """@echo off
            git diff --name-only %DIFF_BASE%..%DIFF_HEAD% > .changed_2  2>nul || type NUL > .changed_2
            git diff --name-only %DIFF_BASE%...%DIFF_HEAD% > .changed_3 2>nul || type NUL > .changed_3
            type .changed_2 .changed_3 > .changed_all

            rem Use PowerShell for robust case-insensitive filtering:
            powershell -NoProfile -Command ^
              "(Get-Content '.changed_all' | ForEach-Object { \$_ -replace '\\\\','/' } | " ^
              "  ForEach-Object { \$_.ToLower() } | " ^
              "  Where-Object { \$_ -match '(^|/ )?readme(\\..*)?\$' -or \$_ -match '(^|/)readme(\\..*)?\$' } | " ^
              "  Sort-Object -Unique) | Set-Content 'readme.changed.list' -Encoding ascii"
            """
          }

          // Load the list into Groovy and set the gate
          List<String> changed = fileExists('readme.changed.list')
            ? readFile('readme.changed.list').trim().split('\\r?\\n') as List<String>
            : []

          // Handle case where file exists but is empty
          if (changed.size() == 1 && changed[0].trim() == '') {
            changed = []
          }

          echo "Changed README files (final): ${changed}"
          env.README_CHANGED = changed && changed.size() > 0 ? 'true' : 'false'
          echo "README_CHANGED=${env.README_CHANGED}"
        }
      }
    }

    // 5) Show diff and counts only if README changed
    stage('Show README diff') {
      when { expression { env.README_CHANGED?.trim() == 'true' } }
      steps {
        script {
          String base = env.DIFF_BASE
          String head = env.DIFF_HEAD

          if (isUnix()) {
            sh """
              set -e
              echo "────────────────────────────────────────"
              echo "Changed lines in README (unified=0):"
              echo "────────────────────────────────────────"
              if [ -s readme.changed.list ]; then
                git --no-pager diff --no-color -U0 ${base}..${head} -- \$(sed 's/\\r\\$//' readme.changed.list | tr '\\n' ' ') | tee readme.diff || true
              else
                : > readme.diff
              fi

              echo ""
              echo "────────────────────────────────────────"
              echo "Summary (added   removed   filename):"
              echo "────────────────────────────────────────"
              if [ -s readme.changed.list ]; then
                git --no-pager diff --no-color --numstat ${base}..${head} -- \$(sed 's/\\r\\$//' readme.changed.list | tr '\\n' ' ') | tee readme.numstat || true
              else
                : > readme.numstat
              fi

              echo ""
              echo "────────────────────────────────────────"
              echo "Only added lines:"
              echo "────────────────────────────────────────"
              if [ -s readme.changed.list ]; then
                git --no-pager diff -U0 ${base}..${head} -- \$(sed 's/\\r\\$//' readme.changed.list | tr '\\n' ' ') \\
                  | sed -n 's/^+[^+]/&/p' | sed 's/^+//' | tee readme.added || true
                git --no-pager diff -U0 ${base}..${head} -- \$(sed 's/\\r\\$//' readme.changed.list | tr '\\n' ' ') \\
                  | sed -n 's/^-[^-]/&/p' | sed 's/^-//' | tee readme.removed || true
              else
                : > readme.added
                : > readme.removed
              fi

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
            setlocal EnableDelayedExpansion

            echo ────────────────────────────────────────
            echo Changed lines in README (unified=0):
            echo ────────────────────────────────────────
            set FILES=
            for /f "usebackq delims=" %%F in ("readme.changed.list") do set FILES=!FILES! "%%F"
            if not "%FILES%"=="" (
              git --no-pager diff --no-color -U0 %DIFF_BASE%..%DIFF_HEAD% -- %FILES% > readme.diff 2>nul || type NUL > readme.diff
            ) else (
              type NUL > readme.diff
            )

            echo.
            echo ────────────────────────────────────────
            echo Summary (added   removed   filename):
            echo ────────────────────────────────────────
            if not "%FILES%"=="" (
              git --no-pager diff --no-color --numstat %DIFF_BASE%..%DIFF_HEAD% -- %FILES% > readme.numstat 2>nul || type NUL > readme.numstat
            ) else (
              type NUL > readme.numstat
            )

            echo.
            echo ────────────────────────────────────────
            echo Only added / removed lines:
            echo ────────────────────────────────────────
            if not "%FILES%"=="" (
              git --no-pager diff -U0 %DIFF_BASE%..%DIFF_HEAD% -- %FILES% | findstr /R "^[+]" | findstr /V "^[+][+]" > readme.added 2>nul || type NUL > readme.added
              git --no-pager diff -U0 %DIFF_BASE%..%DIFF_HEAD% -- %FILES% | findstr /R "^[-]" | findstr /V "^[-][-]" > readme.removed 2>nul || type NUL > readme.removed
            ) else (
              type NUL > readme.added
              type NUL > readme.removed
            )

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

            endlocal
            """
          }

          archiveArtifacts artifacts: 'readme.*', fingerprint: true, allowEmptyArchive: true
        }
      }
    }

    // 6) Run tests only if README changed
    stage('Test') {
      when { expression { env.README_CHANGED?.trim() == 'true' } }
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

    // 7) Persist current head for the next run’s base
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
