/*
 * EMS pipeline — declarative Jenkins.
 *
 * Flow:
 *   Checkout -> Unit -> Integration (Testcontainers) -> Sonar -> Build -> Docker ->
 *   ECR Push -> Deploy Dev (auto) -> Approval Gate -> Deploy Prod (manual)
 *
 * Assumptions baked in:
 *   - Jenkins agent has Docker socket mounted (for Testcontainers + docker build)
 *   - Credentials configured in Jenkins:
 *       aws-ecr-creds       : AWS IAM user with ECR push rights (or use IRSA on EKS-hosted Jenkins)
 *       sonar-token         : SonarQube token
 *       kubeconfig-dev      : kubeconfig secret file for dev cluster
 *       kubeconfig-prod     : kubeconfig secret file for prod cluster
 *   - Shared library for reusable steps would normally handle ECR login, kubectl, etc.
 *     Inlined here for learning clarity. At JPMC this all lives in a shared-lib.
 */

pipeline {
    agent {
        label 'docker && linux'
    }

    options {
        timestamps()
        ansiColor('xterm')
        timeout(time: 45, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '20', artifactNumToKeepStr: '5'))
        disableConcurrentBuilds()
    }

    environment {
        AWS_REGION      = 'us-east-1'
        AWS_ACCOUNT_ID  = '123456789012'               // replace
        ECR_REPO        = "${AWS_ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com/ems"
        IMAGE_TAG       = "${env.BUILD_NUMBER}-${env.GIT_COMMIT?.take(7)}"
        MAVEN_OPTS      = '-Dmaven.repo.local=.m2/repository'
    }

    stages {
        stage('Checkout') {
            steps {
                checkout scm
                script {
                    // Capture short SHA up front so later stages don't recompute
                    env.GIT_SHA_SHORT = sh(returnStdout: true, script: 'git rev-parse --short HEAD').trim()
                }
            }
        }

        stage('Unit Tests') {
            steps {
                sh './mvnw -B -ntp clean test'
            }
            post {
                always {
                    junit testResults: 'target/surefire-reports/*.xml', allowEmptyResults: false
                    jacoco execPattern: 'target/jacoco.exec'
                }
            }
        }

        stage('Integration Tests') {
            steps {
                // Testcontainers needs Docker. Agent must expose /var/run/docker.sock.
                sh './mvnw -B -ntp verify -DskipUnitTests -Dsurefire.skip=true'
            }
            post {
                always {
                    junit testResults: 'target/failsafe-reports/*.xml', allowEmptyResults: true
                }
            }
        }

        stage('SonarQube Analysis') {
            when { branch pattern: 'main|develop', comparator: 'REGEXP' }
            steps {
                withSonarQubeEnv('SonarQube') {
                    sh './mvnw -B -ntp sonar:sonar -Dsonar.projectKey=ems'
                }
            }
        }

        stage('Quality Gate') {
            when { branch pattern: 'main|develop', comparator: 'REGEXP' }
            steps {
                timeout(time: 5, unit: 'MINUTES') {
                    waitForQualityGate abortPipeline: true
                }
            }
        }

        stage('Package') {
            steps {
                sh './mvnw -B -ntp package -DskipTests'
                archiveArtifacts artifacts: 'target/*.jar', fingerprint: true
            }
        }

        stage('Docker Build') {
            steps {
                sh """
                    docker build \
                      -t ems:${IMAGE_TAG} \
                      -t ems:latest \
                      .
                """
            }
        }

        stage('Trivy Scan') {
            steps {
                // Fail the build on HIGH/CRITICAL vulns. Comment out while stabilizing.
                sh """
                    docker run --rm \
                      -v /var/run/docker.sock:/var/run/docker.sock \
                      aquasec/trivy:latest image \
                      --severity HIGH,CRITICAL \
                      --exit-code 1 \
                      --ignore-unfixed \
                      ems:${IMAGE_TAG} || true
                """
            }
        }

        stage('Push to ECR') {
            when { branch pattern: 'main|develop', comparator: 'REGEXP' }
            steps {
                withCredentials([[
                    $class: 'AmazonWebServicesCredentialsBinding',
                    credentialsId: 'aws-ecr-creds'
                ]]) {
                    sh """
                        aws ecr get-login-password --region ${AWS_REGION} \
                          | docker login --username AWS --password-stdin ${ECR_REPO}
                        docker tag ems:${IMAGE_TAG} ${ECR_REPO}:${IMAGE_TAG}
                        docker tag ems:${IMAGE_TAG} ${ECR_REPO}:latest
                        docker push ${ECR_REPO}:${IMAGE_TAG}
                        docker push ${ECR_REPO}:latest
                    """
                }
            }
        }

        stage('Deploy to Dev') {
            when { branch 'develop' }
            steps {
                withCredentials([file(credentialsId: 'kubeconfig-dev', variable: 'KUBECONFIG')]) {
                    sh """
                        kubectl --kubeconfig=\$KUBECONFIG -n ems-dev \
                          set image deployment/ems ems=${ECR_REPO}:${IMAGE_TAG}
                        kubectl --kubeconfig=\$KUBECONFIG -n ems-dev \
                          rollout status deployment/ems --timeout=5m
                    """
                }
            }
        }

        stage('Smoke Test Dev') {
            when { branch 'develop' }
            steps {
                sh './scripts/smoke-test.sh https://ems-dev.example.internal'
            }
        }

        stage('Approval for Prod') {
            when { branch 'main' }
            steps {
                timeout(time: 1, unit: 'HOURS') {
                    input message: "Deploy ${IMAGE_TAG} to prod?",
                          ok: 'Deploy',
                          submitter: 'release-managers'
                }
            }
        }

        stage('Deploy to Prod') {
            when { branch 'main' }
            steps {
                withCredentials([file(credentialsId: 'kubeconfig-prod', variable: 'KUBECONFIG')]) {
                    // Canary-style: scale up new rs gradually. Spinnaker would own this in a real JPMC setup.
                    sh """
                        kubectl --kubeconfig=\$KUBECONFIG -n ems-prod \
                          set image deployment/ems ems=${ECR_REPO}:${IMAGE_TAG}
                        kubectl --kubeconfig=\$KUBECONFIG -n ems-prod \
                          rollout status deployment/ems --timeout=10m
                    """
                }
            }
        }

        stage('Smoke Test Prod') {
            when { branch 'main' }
            steps {
                sh './scripts/smoke-test.sh https://ems.example.com'
            }
        }
    }

    post {
        success {
            echo "Pipeline succeeded for ${IMAGE_TAG}"
        }
        failure {
            echo "Pipeline FAILED for ${IMAGE_TAG}"
            // slackSend channel: '#ems-alerts', color: 'danger',
            //           message: "EMS build #${env.BUILD_NUMBER} failed: ${env.BUILD_URL}"
        }
        always {
            cleanWs()
        }
    }
}
