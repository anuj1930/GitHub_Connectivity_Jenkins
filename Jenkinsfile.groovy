pipeline {
    agent any

    environment {
        JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.1.12-hotspot' // Replace with the exact name you used in Jenkins
        PATH = "${JAVA_HOME}/bin:${env.PATH}"
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Compile') {
            steps {
                echo '🔧 Compiling Java file...'
                sh 'javac Main.java'
            }
        }

        stage('Run') {
            steps {
                echo '🚀 Running Java file...'
                sh 'java Main'
            }
        }
    }

    post {
        success {
            echo '✅ Java file executed successfully.'
        }
        failure {
            echo '❌ Build failed. Please check the logs.'
        }
    }
}
