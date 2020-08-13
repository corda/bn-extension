import static com.r3.build.BuildControl.killAllExistingBuildsForJob
@Library('existing-build-control')
import static com.r3.build.BuildControl.killAllExistingBuildsForJob

killAllExistingBuildsForJob(env.JOB_NAME, env.BUILD_NUMBER.toInteger())

pipeline {
    agent { label 'standard' }
    options { timestamps() }
    environment {
        EXECUTOR_NUMBER = "${env.EXECUTOR_NUMBER}"
    }
    stages {
        stage('Detekt') {
            steps {
                sh "./gradlew clean detekt --info"
            }
        }
        stage('Unit Tests') {
            steps {
                sh "./gradlew clean test --info"
            }
        }
        stage('Integration Tests') {
            steps {
                sh "./gradlew clean integrationTest --info"
            }
        }
    }
    post {
        always {
            junit '**/build/test-results/**/*.xml'
        }
        cleanup {
            deleteDir() /* clean up our workspace */
        }
    }
}