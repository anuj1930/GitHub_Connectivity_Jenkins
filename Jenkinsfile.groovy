pipeline {
    agent any

    stages {
        stage('Checkout') {
            steps {
                git url: 'https://github.com/anuj1930/GitHub_Connectivity_Jenkins.git', branch: 'main'
            }
        }

        stage('Compile') {
            steps {
                script {
                    sh 'javac Main.java'
                }
            }
        }

        stage('Run') {
            steps {
                script {
                    sh 'java Main'
                }
            }
        }
    }

    post {
        failure {
            echo 'Build failed. Please check the logs.'
        }
        success {
            echo 'Java file executed successfully.'
        }
    }
}
