/*
 * EMS pipeline. Reads jules.yml for app metadata and the Spinnaker webhook
 * target, builds + pushes the image, then POSTs to Spinnaker to trigger the
 * ECS Fargate deploy. Scan / build stages are kept as commented blocks below
 * so they can be re-enabled one-by-one once their Jenkins creds are wired.
 */

pipeline {

    agent any

    options {
        timestamps()
        ansiColor('xterm')
        timeout(time: 45, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '20', artifactNumToKeepStr: '5'))
        disableConcurrentBuilds()
    }

    environment {
        AWS_REGION     = 'us-east-1'
        AWS_ACCOUNT_ID = credentials('aws-account-id')
        ECR_REGISTRY   = "${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"
        MAVEN_OPTS     = '-Dmaven.repo.local=.m2/repository'

        CI = 'true'
        TESTCONTAINERS_RYUK_DISABLED = 'true'
    }

    stages {

        stage('Init') {
            steps {
                checkout scm

                script {
                    def jules = readYaml(file: 'jules.yml')

                    env.APP_NAME = jules.application.name
                    env.APP_ID   = jules.application['app-id']
                    env.LOB      = jules.application.lob
                    env.ECR_REPO = jules.package.registry.repository

                    env.GIT_SHA_SHORT = sh(
                        script: 'git rev-parse --short HEAD',
                        returnStdout: true
                    ).trim()

                    env.IMAGE_TAG = "${env.BUILD_NUMBER}-${env.GIT_SHA_SHORT}"
                    env.IMAGE_URI = "${env.ECR_REGISTRY}/${env.ECR_REPO}:${env.IMAGE_TAG}"

                    env.SPINNAKER_BASE_URL = jules.deploy.spinnaker['base-url']
                    env.SPINNAKER_SOURCE   = jules.deploy.spinnaker['webhook-source']

                    env.TRIVY_SEVERITY = jules.scan.container.severity
                }
            }
        }

        stage('Prepare Maven Wrapper') {
            steps {
                sh 'chmod +x mvnw'
            }
        }

        /*
         * =========================
         * ENFORCE MAIN (FIXED)
         * =========================
         */
        stage('Enforce Main Branch') {
            steps {
                script {
                    def result = sh(
                        script: "git branch -r --contains HEAD | grep origin/main || true",
                        returnStdout: true
                    ).trim()

                    echo "Branch detection: ${result}"

                    if (!result) {
                        error("❌ ONLY 'main' can deploy. This commit is NOT from main.")
                    }
                }
            }
        }

        /*
         * =========================
         * SONAR (skip tests)
         * =========================
         */
//         stage('SAST (Sonar)') {
//             steps {
//                 withCredentials([string(credentialsId: 'sonar-token', variable: 'SONAR_TOKEN')]) {
//                     timeout(time: 5, unit: 'MINUTES') {
//                         sh '''
//                           ./mvnw -B -ntp clean verify \
//                             org.sonarsource.scanner.maven:sonar-maven-plugin:4.0.0.4121:sonar \
//                             -Dsonar.projectKey=sarkr72_practice \
//                             -Dsonar.organization=sarkr72 \
//                             -Dsonar.host.url=https://sonarcloud.io \
//                             -Dsonar.token=$SONAR_TOKEN
//                         '''
//                     }
//                 }
//             }
//         }

        /*
         * =========================
         * BUILD & PUSH IMAGE
         * =========================
         */
//         stage('Build & Push Image (Jib)') {
//             steps {
//                 withCredentials([
//                     string(credentialsId: 'aws-access-key-id',     variable: 'AWS_ACCESS_KEY_ID'),
//                     string(credentialsId: 'aws-secret-access-key', variable: 'AWS_SECRET_ACCESS_KEY')
//                 ]) {
//                     sh '''
//                         ECR_PASSWORD=$(aws ecr get-login-password --region ${AWS_REGION})
//
//                         ./mvnw -B -ntp -DskipTests compile \
//                           com.google.cloud.tools:jib-maven-plugin:build \
//                           -Dimage=${IMAGE_URI} \
//                           -Djib.to.tags=${IMAGE_TAG},latest \
//                           -Djib.to.auth.username=AWS \
//                           -Djib.to.auth.password=$ECR_PASSWORD
//                     '''
//                 }
//             }
//         }

        /*
         * =========================
         * IMAGE SCAN (TRIVY)
         * =========================
         */
//         stage('Image Scan (Trivy)') {
//             steps {
//                 withCredentials([
//                     string(credentialsId: 'aws-access-key-id',     variable: 'AWS_ACCESS_KEY_ID'),
//                     string(credentialsId: 'aws-secret-access-key', variable: 'AWS_SECRET_ACCESS_KEY')
//                 ]) {
//                     sh '''
//                         trivy image \
//                           --severity ${TRIVY_SEVERITY} \
//                           --exit-code 1 \
//                           --ignore-unfixed \
//                           ${IMAGE_URI}
//                     '''
//                 }
//             }
//         }

        /*
         * =========================
         * DEPLOY (SPINNAKER)
         * =========================
         */
        stage('Trigger Spinnaker') {
            steps {
                sh '''
                    curl -fSL -X POST \
                      -H "Content-Type: application/json" \
                      -d "{\\"parameters\\":{\\"imageTag\\":\\"${IMAGE_TAG}\\",\\"appId\\":\\"${APP_ID}\\"}}" \
                      "${SPINNAKER_BASE_URL}/webhooks/webhook/${SPINNAKER_SOURCE}"
                '''
            }
        }

        /*
         * NOTE: The BlazeMeter performance test stage has moved out of this
         * pipeline. Spinnaker now drives the perf flow:
         *   Deploy Perf -> Smoke Perf -> trigger Jenkins job 'ems-perf-load-test'
         * That job is defined by Jenkinsfile.perf in this repo.
         */

    } // ✅ stages CLOSED HERE

    /*
     * =========================
     * POST
     * =========================
     */
    post {
        success {
            echo "✓ SUCCESS — ${IMAGE_URI}"
        }
        failure {
            echo "✗ FAILED — ${IMAGE_TAG}"
        }
        always {
            cleanWs()
        }
    }
}