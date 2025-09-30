pipeline {
    agent any

    stages {
        stage('Checkout') {
            steps {
                git branch: 'main', url: 'https://github.com/anuj1930/GitHub_Connectivity_Jenkins.git'
            }
        }

        stage('Build') {
            steps {
                echo 'Building the project...'
                // Example: For Java
                sh 'mvn clean install'
            }
        }

        stage('Test') {
            steps {
                echo 'Running tests...'
                // Example: For Java
                sh 'mvn test'
            }
            post {
                always {
                    junit '**/target/surefire-reports/*.xml'
                }
            }
        }
    }
}