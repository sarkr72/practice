// /*
//  * EMS pipeline — Jenkinsfile is the Jules pipeline shell.
//  *
//  * This file reads jules.yml at the start of every build and uses it as the
//  * source of truth for app metadata, scan thresholds, and the Spinnaker
//  * pipeline name to trigger. Change pipeline behavior in jules.yml; only
//  * change this Groovy file when you change the pipeline shape itself.
//  *
//  * Flow:
//  *   Init (read jules.yml) -> Unit -> Integration -> Scans (parallel) ->
//  *   Jib Build & Push -> Trivy Image Scan -> Trigger Spinnaker
//  *
//  * Spinnaker owns dev deploy -> smoke -> manual judgment -> prod deploy.
//  *
//  * Required Jenkins credentials:
//  *   aws-account-id              : 12-digit account ID (string)
//  *   aws-ecr-creds               : AWS IAM with ECR push (or use IRSA)
//  *   sonar-token                 : SonarQube token
//  *   nvd-api-key                 : NIST NVD API key for dependency-check
//  *   spinnaker-webhook-token     : Token in X-Spinnaker-Token header
//  *
//  * Required agent capabilities:
//  *   - Docker socket (Testcontainers + Trivy)
//  *   - Maven Wrapper (./mvnw)
//  *   - aws CLI (for ECR auth pre-Jib)
//  *   - trivy on PATH
//  *
//  * NOTE: At JPMC the heavy lifting (ECR login, Spinnaker trigger, Slack notif)
//  * lives in a shared library. Inlined here so the wiring is visible.
//  */
//
// // Shared library for ECR login, Spinnaker trigger, Slack notifs.
// // A missing library reference fails the pipeline at PARSE time (before any stage
// // runs), so this is commented out by default. Uncomment ONCE the library is
// // registered in: Manage Jenkins -> System -> Global Pipeline Libraries.
// // @Library('jules-shared-lib') _
//
// pipeline {
// //     agent { label 'docker && linux' }
//        agent any
//
//     options {
//         timestamps()
//         ansiColor('xterm')
//         timeout(time: 45, unit: 'MINUTES')
//         buildDiscarder(logRotator(numToKeepStr: '20', artifactNumToKeepStr: '5'))
//         disableConcurrentBuilds()
//     }
//
//     environment {
//         AWS_REGION     = 'us-east-1'
//         AWS_ACCOUNT_ID = credentials('aws-account-id')
//         ECR_REGISTRY   = "${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"
//         MAVEN_OPTS     = '-Dmaven.repo.local=.m2/repository'
//     }
//
//     stages {
//
//         // -------------------------------------------------------------------
//         // Init — read jules.yml, compute image tag, expose everything to later stages
//         // -------------------------------------------------------------------
//         stage('Init') {
//             steps {
//                 checkout scm
//                 script {
//                     def jules = readYaml(file: 'jules.yml')
//
//                     // App metadata
//                     env.APP_NAME    = jules.application.name
//                     env.APP_ID      = jules.application['app-id']
//                     env.LOB         = jules.application.lob
//
//                     // Image coordinates
//                     env.ECR_REPO        = jules.package.registry.repository
//                     env.GIT_SHA_SHORT   = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
//                     env.GIT_BRANCH_SAFE = (env.BRANCH_NAME ?: 'local').replaceAll('[^A-Za-z0-9._-]', '-')
//                     env.IMAGE_TAG       = "${env.BUILD_NUMBER}-${env.GIT_SHA_SHORT}"
//                     env.IMAGE_URI       = "${env.ECR_REGISTRY}/${env.ECR_REPO}:${env.IMAGE_TAG}"
//
//                     // Spinnaker handoff
//                     env.SPINNAKER_BASE_URL = jules.deploy.spinnaker['base-url']
//                     env.SPINNAKER_APP      = jules.deploy.spinnaker.application
//                     env.SPINNAKER_SOURCE   = jules.deploy.spinnaker['webhook-source']
//
//                     // Scan thresholds
//                     env.SCA_FAIL_CVSS      = jules.scan.sca['fail-on-cvss'].toString()
//                     env.TRIVY_SEVERITY     = jules.scan.container.severity
//
//                     echo """
//                         |================================================
//                         | Application : ${env.APP_NAME} (${env.APP_ID})
//                         | LOB         : ${env.LOB}
//                         | Branch      : ${env.GIT_BRANCH_SAFE}
//                         | Image       : ${env.IMAGE_URI}
//                         |================================================
//                     """.stripMargin()
//                 }
//             }
//         }
//
//         // -------------------------------------------------------------------
//         // Tests
//         // -------------------------------------------------------------------
//         stage('Unit Tests') {
//             steps { sh './mvnw -B -ntp test' }
//             post {
//                 always {
//                     junit testResults: 'target/surefire-reports/*.xml', allowEmptyResults: false
//                     jacoco execPattern: 'target/jacoco.exec'
//                 }
//             }
//         }
//
//         stage('Integration Tests') {
//             steps {
//                 // Testcontainers needs Docker — the agent must expose /var/run/docker.sock.
//                 // -DskipUnitTests=true flips the pom property; surefire is gated on it
//                 // so we don't double-run unit tests in the verify phase.
//                 sh './mvnw -B -ntp verify -DskipUnitTests=true'
//             }
//             post {
//                 always {
//                     junit testResults: 'target/failsafe-reports/*.xml', allowEmptyResults: true
//                 }
//             }
//         }
//
//         // -------------------------------------------------------------------
//         // Scans — parallel, all gating per jules.yml
//         // -------------------------------------------------------------------
// stage('Scans') {
//     parallel {
//
//         stage('SAST (Sonar)') {
//             when { branch pattern: 'main|develop', comparator: 'REGEXP' }
//             steps {
//                 withSonarQubeEnv('SonarQube') {
//                     sh "./mvnw -B -ntp sonar:sonar -Dsonar.projectKey=${env.APP_NAME}"
//                 }
//                 timeout(time: 5, unit: 'MINUTES') {
//                     waitForQualityGate abortPipeline: true
//                 }
//             }
//         }
//
//     }
// }
// //         stage('Scans') {
// //             parallel {
// //
// //                 stage('SAST (Sonar)') {
// //                     when { branch pattern: 'main|develop', comparator: 'REGEXP' }
// //                     steps {
// //                         withSonarQubeEnv('SonarQube') {
// //                             sh "./mvnw -B -ntp sonar:sonar -Dsonar.projectKey=${env.APP_NAME}"
// //                         }
// //                         timeout(time: 5, unit: 'MINUTES') {
// //                             waitForQualityGate abortPipeline: true
// //                         }
// //                     }
// //                 }
//
// //                 stage('SCA (Dependency-Check)') {
// //                     steps {
// //                         // dependency-check 10+ requires an NVD API key; without one,
// //                         // NVD throttles requests and a clean cache run takes 30+ min.
// //                         // Get a free key from https://nvd.nist.gov/developers/request-an-api-key
// //                         // and add it to Jenkins as Secret text id 'nvd-api-key'.
// //                         withCredentials([string(credentialsId: 'nvd-api-key', variable: 'NVD_KEY')]) {
// //                             sh """
// //                                 ./mvnw -B -ntp org.owasp:dependency-check-maven:check \\
// //                                   -DfailBuildOnCVSS=${env.SCA_FAIL_CVSS} \\
// //                                   -DskipTestScope=true \\
// //                                   -Dnvd.api.key=\$NVD_KEY
// //                             """
// //                         }
// //                     }
// //                     post {
// //                         always {
// //                             archiveArtifacts artifacts: 'target/dependency-check-report.html',
// //                                              allowEmptyArchive: true
// //                         }
// //                     }
// //                 }
// //
// //                 stage('Secret Scan (gitleaks)') {
// //                     steps {
// //                         sh '''
// //                             docker run --rm -v "$(pwd):/repo" zricethezav/gitleaks:latest \
// //                               detect --source=/repo --no-git --redact --exit-code 1
// //                         '''
// //                     }
// //                 }
// //
// //                 stage('IaC Scan (tfsec)') {
// //                     steps {
// //                         sh '''
// //                             docker run --rm -v "$(pwd)/terraform:/src" aquasec/tfsec:latest \
// //                               /src --minimum-severity HIGH
// //                         '''
// //                     }
// //                 }
// //             }
// //         }
//
//         // -------------------------------------------------------------------
//         // Build & push image with Jib — NO Docker build, NO Docker push.
//         // Jib talks directly to ECR. Auth via aws ecr get-login-password fed
//         // to Jib's username/password flags so we don't need a credHelper binary.
//         // -------------------------------------------------------------------
//         stage('Build & Push Image (Jib)') {
//             when { branch pattern: 'main|develop', comparator: 'REGEXP' }
//             steps {
//                 withCredentials([[$class: 'AmazonWebServicesCredentialsBinding',
//                                   credentialsId: 'aws-ecr-creds']]) {
//                     sh """
//                         ECR_PASSWORD=\$(aws ecr get-login-password --region ${AWS_REGION})
//
//                         ./mvnw -B -ntp compile com.google.cloud.tools:jib-maven-plugin:build \\
//                           -Dimage=${IMAGE_URI} \\
//                           -Djib.to.tags=${IMAGE_TAG},latest,${GIT_BRANCH_SAFE} \\
//                           -Djib.to.auth.username=AWS \\
//                           -Djib.to.auth.password=\$ECR_PASSWORD \\
//                           -Djib.container.labels.build-id=${BUILD_NUMBER} \\
//                           -Djib.container.labels.git-sha=${GIT_SHA_SHORT} \\
//                           -Djib.container.labels.app-id=${APP_ID}
//                     """
//                 }
//             }
//         }
//
//         // -------------------------------------------------------------------
//         // Image scan AFTER push — scans what's actually in the registry
//         // -------------------------------------------------------------------
//         stage('Image Scan (Trivy)') {
//             when { branch pattern: 'main|develop', comparator: 'REGEXP' }
//             steps {
//                 withCredentials([[$class: 'AmazonWebServicesCredentialsBinding',
//                                   credentialsId: 'aws-ecr-creds']]) {
//                     sh """
//                         aws ecr get-login-password --region ${AWS_REGION} \\
//                           | docker login --username AWS --password-stdin ${ECR_REGISTRY}
//
//                         trivy image \\
//                           --severity ${TRIVY_SEVERITY} \\
//                           --exit-code 1 \\
//                           --ignore-unfixed \\
//                           --format table \\
//                           ${IMAGE_URI}
//                     """
//                 }
//             }
//         }
//
//         // -------------------------------------------------------------------
//         // Hand off to Spinnaker for CD. Jenkins doesn't deploy. Spinnaker owns
//         // dev -> smoke -> manual judgment -> prod.
//         // -------------------------------------------------------------------
//         stage('Trigger Spinnaker') {
//             when { branch pattern: 'main|develop', comparator: 'REGEXP' }
//             steps {
//                 withCredentials([string(credentialsId: 'spinnaker-webhook-token',
//                                         variable: 'SPIN_TOKEN')]) {
//                     sh """
//                         curl -fSL -X POST \\
//                           -H 'Content-Type: application/json' \\
//                           -H "X-Spinnaker-Token: \${SPIN_TOKEN}" \\
//                           -d '{
//                                 "parameters": {
//                                   "imageTag":    "${IMAGE_TAG}",
//                                   "branch":      "${GIT_BRANCH_SAFE}",
//                                   "appId":       "${APP_ID}",
//                                   "buildUrl":    "${BUILD_URL}",
//                                   "ecrRegistry": "${ECR_REGISTRY}",
//                                   "ecrRepo":     "${ECR_REPO}"
//                                 }
//                               }' \\
//                           "${SPINNAKER_BASE_URL}/webhooks/webhook/${SPINNAKER_SOURCE}"
//                     """
//                 }
//                 echo "Spinnaker pipeline triggered: ${SPINNAKER_BASE_URL}/#/applications/${SPINNAKER_APP}/executions"
//             }
//         }
//     }
//
//     post {
//         success {
//             echo "✓ Pipeline succeeded — image: ${IMAGE_URI}"
//         }
//         failure {
//             echo "✗ Pipeline FAILED for ${IMAGE_TAG}"
//             // slackSend channel: '#ems-alerts', color: 'danger',
//             //           message: "EMS build #${env.BUILD_NUMBER} failed: ${env.BUILD_URL}"
//         }
//         always {
//             cleanWs()
//         }
//     }
// }

/*
 * EMS pipeline — Jenkinsfile is the Jules pipeline shell.
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

        // 🔥 FIX: prevents Testcontainers from trying Docker in Jenkins
        CI              = 'true'
    }

    stages {

        stage('Init') {
            steps {
                checkout scm

                script {
                    def jules = readYaml(file: 'jules.yml')

                    env.APP_NAME   = jules.application.name
                    env.APP_ID     = jules.application['app-id']
                    env.LOB        = jules.application.lob
                    env.ECR_REPO   = jules.package.registry.repository

                    env.GIT_SHA_SHORT = sh(
                        script: 'git rev-parse --short HEAD',
                        returnStdout: true
                    ).trim()

                    env.GIT_BRANCH_SAFE = (env.BRANCH_NAME ?: 'local')
                        .replaceAll('[^A-Za-z0-9._-]', '-')

                    env.IMAGE_TAG = "${env.BUILD_NUMBER}-${env.GIT_SHA_SHORT}"
                    env.IMAGE_URI = "${env.ECR_REGISTRY}/${env.ECR_REPO}:${env.IMAGE_TAG}"

                    env.SPINNAKER_BASE_URL = jules.deploy.spinnaker['base-url']
                    env.SPINNAKER_APP      = jules.deploy.spinnaker.application
                    env.SPINNAKER_SOURCE   = jules.deploy.spinnaker['webhook-source']

                    env.SCA_FAIL_CVSS  = jules.scan.sca['fail-on-cvss'].toString()
                    env.TRIVY_SEVERITY = jules.scan.container.severity

                    echo """
================================================
Application : ${env.APP_NAME} (${env.APP_ID})
LOB         : ${env.LOB}
Branch      : ${env.GIT_BRANCH_SAFE}
Image       : ${env.IMAGE_URI}
================================================
"""
                }
            }
        }

        stage('Prepare Maven Wrapper') {
            steps {
                sh '''
                    chmod +x mvnw
                '''
            }
        }

        stage('Unit Tests') {
            steps {
                sh '''
                    ./mvnw -B -ntp test -Dspring.profiles.active=ci
                '''
            }

            post {
                always {
                    junit testResults: 'target/surefire-reports/*.xml', allowEmptyResults: true
                    jacoco execPattern: 'target/jacoco.exec'
                }
            }
        }

        stage('Integration Tests') {
            steps {
                sh '''
                    ./mvnw -B -ntp verify \
                      -DskipUnitTests=true \
                      -Dspring.profiles.active=ci
                '''
            }

            post {
                always {
                    junit testResults: 'target/failsafe-reports/*.xml', allowEmptyResults: true
                }
            }
        }

        stage('Scans') {
            parallel {

                stage('SAST (Sonar)') {
                    when {
                        branch pattern: 'main|develop', comparator: 'REGEXP'
                    }

                    steps {
                        withSonarQubeEnv('SonarQube') {
                            sh "./mvnw -B -ntp sonar:sonar -Dsonar.projectKey=${env.APP_NAME}"
                        }

                        timeout(time: 5, unit: 'MINUTES') {
                            waitForQualityGate abortPipeline: true
                        }
                    }
                }
            }
        }

        stage('Build & Push Image (Jib)') {
            when {
                branch pattern: 'main|develop', comparator: 'REGEXP'
            }

            steps {
                withCredentials([[$class: 'AmazonWebServicesCredentialsBinding',
                                  credentialsId: 'aws-ecr-creds']]) {

                    sh '''
                        ECR_PASSWORD=$(aws ecr get-login-password --region ${AWS_REGION})

                        ./mvnw -B -ntp compile com.google.cloud.tools:jib-maven-plugin:build \
                          -Dimage=${IMAGE_URI} \
                          -Djib.to.tags=${IMAGE_TAG},latest,${GIT_BRANCH_SAFE} \
                          -Djib.to.auth.username=AWS \
                          -Djib.to.auth.password=$ECR_PASSWORD
                    '''
                }
            }
        }

        stage('Image Scan (Trivy)') {
            when {
                branch pattern: 'main|develop', comparator: 'REGEXP'
            }

            steps {
                sh '''
                    trivy image \
                      --severity ${TRIVY_SEVERITY} \
                      --exit-code 1 \
                      --ignore-unfixed \
                      --format table \
                      ${IMAGE_URI}
                '''
            }
        }

        stage('Trigger Spinnaker') {
            when {
                branch pattern: 'main|develop', comparator: 'REGEXP'
            }

            steps {
                withCredentials([string(credentialsId: 'spinnaker-webhook-token',
                                        variable: 'SPIN_TOKEN')]) {

                    sh '''
                        curl -fSL -X POST \
                          -H "Content-Type: application/json" \
                          -H "X-Spinnaker-Token: $SPIN_TOKEN" \
                          -d "{
                            \"parameters\": {
                              \"imageTag\": \"${IMAGE_TAG}\",
                              \"branch\": \"${GIT_BRANCH_SAFE}\",
                              \"appId\": \"${APP_ID}\",
                              \"buildUrl\": \"${BUILD_URL}\",
                              \"ecrRegistry\": \"${ECR_REGISTRY}\",
                              \"ecrRepo\": \"${ECR_REPO}\"
                            }
                          }" \
                          "${SPINNAKER_BASE_URL}/webhooks/webhook/${SPINNAKER_SOURCE}"
                    '''
                }
            }
        }
    }

    post {
        success {
            echo "✓ Pipeline succeeded — ${IMAGE_URI}"
        }

        failure {
            echo "✗ Pipeline FAILED — ${IMAGE_TAG}"
        }

        always {
            cleanWs()
        }
    }
}