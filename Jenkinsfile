// Jenkins Pipeline for cicd-demo — Phase 1 (Docker Compose)
// See docs/阶段1部署操作手册-GitLab仓与Jenkins.md
// Hosts use docker-compose v1 (not `docker compose` plugin).

pipeline {
  agent any

  environment {
    APP_DIR = 'app'
    REGISTRY_IMAGE = "${env.REGISTRY_IMAGE ?: '10.0.0.13:5050/root/cicd-test'}"
    IMAGE_TAG = "${env.GIT_COMMIT?.take(7) ?: 'local'}"
  }

  options {
    timestamps()
    disableConcurrentBuilds()
  }

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

    stage('Build & Push Image') {
      when {
        anyOf {
          branch 'main'
          tag pattern: 'v.*', comparator: 'REGEXP'
        }
      }
      steps {
        dir(env.APP_DIR) {
          sh '''
            set -e
            mvn -B -DskipTests package
            docker build -t "$REGISTRY_IMAGE:$IMAGE_TAG" .
          '''
          withCredentials([usernamePassword(credentialsId: 'registry-credentials', usernameVariable: 'REG_USER', passwordVariable: 'REG_PASS')]) {
            sh '''
              set -e
              echo "$REG_PASS" | docker login -u "$REG_USER" --password-stdin 10.0.0.13:5050
              docker push "$REGISTRY_IMAGE:$IMAGE_TAG"
            '''
          }
        }
      }
    }

    stage('Deploy Dev') {
      when { branch 'main' }
      steps {
        sshagent(credentials: ['deploy-ssh']) {
          sh '''
            set -e
            ssh -o StrictHostKeyChecking=accept-new deploy@$APP_DEV_HOST "
              set -e
              cd /opt/cicd-demo-dev
              sed -i 's|^IMAGE_TAG=.*|IMAGE_TAG=$IMAGE_TAG|' .env
              sed -i 's|^REGISTRY_IMAGE=.*|REGISTRY_IMAGE=$REGISTRY_IMAGE|' .env
              export IMAGE_TAG=$IMAGE_TAG REGISTRY_IMAGE=$REGISTRY_IMAGE
              # compose v1 + Docker 25+ recreate bug (KeyError ContainerConfig)
              docker-compose down --remove-orphans || true
              docker rm -f \$(docker ps -aq --filter name=cicd-demo-dev) 2>/dev/null || true
              docker-compose pull
              docker-compose up -d
            "
          '''
        }
      }
    }

    stage('Deploy Test') {
      when { branch 'main' }
      steps {
        sshagent(credentials: ['deploy-ssh']) {
          sh '''
            set -e
            ssh -o StrictHostKeyChecking=accept-new deploy@$APP_DEV_HOST "
              set -e
              cd /opt/cicd-demo-test
              sed -i 's|^IMAGE_TAG=.*|IMAGE_TAG=$IMAGE_TAG|' .env
              sed -i 's|^REGISTRY_IMAGE=.*|REGISTRY_IMAGE=$REGISTRY_IMAGE|' .env
              export IMAGE_TAG=$IMAGE_TAG REGISTRY_IMAGE=$REGISTRY_IMAGE
              docker-compose down --remove-orphans || true
              docker rm -f \$(docker ps -aq --filter name=cicd-demo-test) 2>/dev/null || true
              docker-compose pull
              docker-compose up -d
            "
          '''
        }
      }
    }

    stage('Approve Prod') {
      when { tag pattern: 'v.*', comparator: 'REGEXP' }
      steps {
        input message: "Deploy ${IMAGE_TAG} to production (8092)?", ok: 'Deploy'
      }
    }

    stage('Deploy Prod') {
      when { tag pattern: 'v.*', comparator: 'REGEXP' }
      steps {
        sshagent(credentials: ['deploy-ssh']) {
          sh '''
            set -e
            ssh -o StrictHostKeyChecking=accept-new deploy@$APP_PROD_HOST "
              set -e
              cd /opt/cicd-demo-prod
              sed -i 's|^IMAGE_TAG=.*|IMAGE_TAG=$IMAGE_TAG|' .env
              sed -i 's|^REGISTRY_IMAGE=.*|REGISTRY_IMAGE=$REGISTRY_IMAGE|' .env
              export IMAGE_TAG=$IMAGE_TAG REGISTRY_IMAGE=$REGISTRY_IMAGE
              docker-compose down --remove-orphans || true
              docker rm -f \$(docker ps -aq --filter name=cicd-demo-prod) 2>/dev/null || true
              docker-compose pull
              docker-compose up -d
            "
          '''
        }
      }
    }
  }

  post {
    success {
      script {
        if (env.NOTIFY_WEBHOOK) {
          sh """
            curl -s -X POST -H 'Content-Type: application/json' \\
              -d '{"msgtype":"text","text":{"content":"[Jenkins] ${env.JOB_NAME} #${env.BUILD_NUMBER} OK tag=${env.IMAGE_TAG}"}}' \\
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
