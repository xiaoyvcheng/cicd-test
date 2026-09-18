// Jenkins Pipeline for cicd-demo — Phase 0 (systemd jar deploy)
// See docs/implement-jenkins.md. Phase 1 Docker stages are commented out.

pipeline {
  agent any

  environment {
    APP_DIR = 'app'
  }

  options {
    timestamps()
    disableConcurrentBuilds()
  }

  // Phase 0: use system JDK/Maven on platform (apt). Configure Jenkins Tools later if needed.

  stages {
    stage('Checkout') {
      steps {
        checkout scm
      }
    }

    stage('Build & Test') {
      steps {
        dir(env.APP_DIR) {
          sh 'mvn -B clean verify'
        }
      }
    }

    stage('Deploy Dev') {
      when { branch 'main' }
      steps {
        sshagent(credentials: ['deploy-ssh']) {
          sh '''
            set -e
            JAR=$(ls app/target/cicd-demo-*.jar | grep -v original | head -n1)
            scp -o StrictHostKeyChecking=accept-new "$JAR" deploy@$APP_DEV_HOST:/opt/cicd-demo/cicd-demo.jar
            ssh -o StrictHostKeyChecking=accept-new deploy@$APP_DEV_HOST 'sudo systemctl restart cicd-demo && sudo systemctl is-active cicd-demo'
          '''
        }
      }
    }

    // --- Phase 1 (Docker) — enable later ---
    // stage('Build & Push Image') { ... }
    // stage('Deploy Test') { ... }
    // stage('Approve Prod') { ... }
    // stage('Deploy Prod') { ... }
  }

  post {
    success {
      script {
        if (env.NOTIFY_WEBHOOK) {
          sh """
            curl -s -X POST -H 'Content-Type: application/json' \\
              -d '{"msgtype":"text","text":{"content":"[Jenkins] ${env.JOB_NAME} #${env.BUILD_NUMBER} OK"}}' \\
              '${env.NOTIFY_WEBHOOK}' || true
          """
        }
      }
    }
    failure {
      script {
        if (env.NOTIFY_WEBHOOK) {
          sh """
            curl -s -X POST -H 'Content-Type: application/json' \\
              -d '{"msgtype":"text","text":{"content":"[Jenkins] ${env.JOB_NAME} #${env.BUILD_NUMBER} failed"}}' \\
              '${env.NOTIFY_WEBHOOK}' || true
          """
        }
      }
    }
  }
}
