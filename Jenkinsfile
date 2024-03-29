#!groovy
/**
 * Jenkins pipeline to build Business Networks Extensions
 */

/**
 * Kill already started job.
 * Assume new commit takes precendence and results from previous
 * unfinished builds are not required.
 * This feature doesn't play well with disableConcurrentBuilds() option
 */
@Library('existing-build-control')
import static com.r3.build.BuildControl.killAllExistingBuildsForJob
import com.r3.build.utils.GitUtils
import groovy.transform.Field

killAllExistingBuildsForJob(env.JOB_NAME, env.BUILD_NUMBER.toInteger())

@Field
GitUtils gitUtils = new GitUtils(this)

/**
 * Sense environment
 */
boolean isReleaseBranch = (env.BRANCH_NAME =~ /^release\/.*/)
boolean isRelease = (env.TAG_NAME =~ /^release-.*/)

pipeline {
    agent {
        docker {
            // Our custom docker image
            image 'build-zulu-openjdk:8'
            label 'docker'
            registryUrl 'https://engineering-docker.software.r3.com/'
            registryCredentialsId 'artifactory-credentials'
            // Used to mount storage from the host as a volume to persist the cache between builds
            args '-v /tmp:/host_tmp'
            alwaysPull true
        }
    }

    parameters {
        booleanParam defaultValue: (isReleaseBranch || isRelease), description: 'Publish artifacts to Artifactory?', name: 'DO_PUBLISH'
    }

    options { timestamps() }

    environment {
        ARTIFACTORY_CREDENTIALS = credentials('artifactory-credentials')
        CORDA_ARTIFACTORY_USERNAME = "${env.CORDA_ARTIFACTORY_USERNAME}"
        CORDA_ARTIFACTORY_PASSWORD = "${env.CORDA_ARTIFACTORY_PASSWORD}"
        ARTIFACTORY_BUILD_NAME = "DisasterRecovery/Jenkins/${!isRelease?"snapshot/":""}${env.BRANCH_NAME}".replaceAll("/", " :: ")
        PUBLISH_REPO = "${isRelease?"corda-releases":"corda-dev"}"
        EXECUTOR_NUMBER = "${env.EXECUTOR_NUMBER}"
        SNYK_TOKEN = credentials("corda4-os-snyk-secret")
        C4_OS_SNYK_ORG_ID = credentials("corda4-os-snyk-org-id")
        GRADLE_USER_HOME = "/host_tmp/gradle"
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
        stage('Snyk Security') {
            when {
                expression { gitUtils.isReleaseTag() || gitUtils.isReleaseCandidate() || gitUtils.isReleaseBranch() }
            }
            steps {
                script {
                    def modulesToScan = ['business-networks-contracts', 'business-networks-workflows']
                    modulesToScan.each { module ->
                        snykSecurityScan(env.SNYK_TOKEN, "--sub-project=$module --configuration-matching='^runtimeClasspath\$' --prune-repeated-subdependencies --debug --target-reference='${env.BRANCH_NAME}' --project-tags=Branch='${env.BRANCH_NAME.replaceAll("[^0-9|a-z|A-Z]+","_")}'", false, true)
                    }
                }
            }
        }
        stage('Generate Snyk License Report') {
            options { retry(2) }
            when {
                expression { gitUtils.isReleaseTag() || gitUtils.isReleaseCandidate() || gitUtils.isReleaseBranch() }
            }
            steps {
                snykLicenseGeneration(env.SNYK_TOKEN, env.C4_OS_SNYK_ORG_ID)
            }
            post {
                always {
                    script {
                        archiveArtifacts artifacts: 'snyk-license-report/*-snyk-license-report.html', allowEmptyArchive: true, fingerprint: true
                    }
                }
            }
        }
        stage('Snyk Delta') {
            when {
                changeRequest()
            }
            steps {
                snykDeltaScan(env.SNYK_TOKEN, env.C4_OS_SNYK_ORG_ID, "--configuration-matching='^runtimeClasspath\$'")
            }
        }
        stage('Publish to Artifactory') {
            when {
                expression { params.DO_PUBLISH }
                beforeAgent true
            }
            steps {
                rtServer(
                        id: 'R3-Artifactory',
                        url: 'https://software.r3.com/artifactory',
                        credentialsId: 'artifactory-credentials'
                )
                rtGradleDeployer(
                        id: 'deployer',
                        serverId: 'R3-Artifactory',
                        repo: env.PUBLISH_REPO
                )
                rtGradleRun(
                        usesPlugin: true,
                        useWrapper: true,
                        switches: '-s --info',
                        tasks: 'artifactoryPublish',
                        deployerId: 'deployer',
                        buildName: env.ARTIFACTORY_BUILD_NAME
                )
                rtPublishBuildInfo(
                        serverId: 'R3-Artifactory',
                        buildName: env.ARTIFACTORY_BUILD_NAME
                )
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