// =============================================================================
// Jenkinsfile — CourseChatbot CI/CD Pipeline
// =============================================================================
//
//  TWO DEPLOY MODES — choose with DEPLOY_MODE parameter:
//
//  LOCAL  (default — no SSH keys needed)
//  ──────
//  Jenkins runs ON the same server as Docker.
//  No SSH, no rsync. Jenkins workspace IS the build directory.
//  Docker commands run directly.
//  ✅ Use this now — no credential setup required.
//
//  REMOTE  (SSH to separate server)
//  ──────
//  Jenkins runs on a different machine, deploys to a remote server via SSH.
//  Requires Jenkins credential ID: server-ssh-key
//  (Manage Jenkins → Credentials → Add → SSH Username with private key)
//  Set up later when SSH keys are ready.
//
//  PREREQUISITES — LOCAL mode
//  ──────────────────────────
//  Jenkins server must have:
//    ✅ Docker + docker compose  installed
//    ✅ Jenkins user added to docker group:
//         sudo usermod -aG docker jenkins && sudo systemctl restart jenkins
//
//  PREREQUISITES — REMOTE mode (later)
//  ────────────────────────────────────
//    ✅ SSH Agent Plugin installed in Jenkins
//    ✅ Credential ID: server-ssh-key  (SSH Username with private key)
//    ✅ rsync installed on Jenkins agent
// =============================================================================

pipeline {
    agent any

    parameters {
        choice(
            name: 'DEPLOY_MODE',
            choices: ['local', 'remote'],
            description: '''Deploy mode:
  local  = Jenkins runs ON the same server as Docker (no SSH keys needed) ✅
  remote = Jenkins SSHes to a separate server (needs server-ssh-key credential)'''
        )
        booleanParam(
            name: 'BUILD_BACKEND',
            defaultValue: true,
            description: '🏗️  Rebuild the Spring Boot backend Docker image'
        )
        booleanParam(
            name: 'BUILD_FRONTEND',
            defaultValue: true,
            description: '🎨  Rebuild the Next.js frontend Docker image'
        )
        booleanParam(
            name: 'RESET_DB',
            defaultValue: false,
            description: '⚠️  DROP and recreate the PostgreSQL volume — ALL DATA WILL BE LOST'
        )
        string(
            name: 'OLLAMA_MODEL',
            defaultValue: 'qwen2.5:0.5b',
            description: '🤖  Ollama model to use'
        )
        string(
            name: 'SERVER_HOST',
            defaultValue: '100.114.88.111',
            description: '🌐  Server IP (used for smoke test URLs and REMOTE mode SSH)'
        )
        string(
            name: 'SERVER_DIR',
            defaultValue: '/home/dell/chatbot',
            description: '📁  Project dir on server (REMOTE mode only — LOCAL uses Jenkins workspace)'
        )
    }

    environment {
        SERVER_USER         = 'dell'
        SSH_CRED_ID         = 'server-ssh-key'
        SSH_OPTS            = '-o StrictHostKeyChecking=no -o ConnectTimeout=30'
        COMPOSE_FILE        = 'docker-compose.server.yml'
        NEXT_PUBLIC_API_URL = "http://${params.SERVER_HOST}:8080"
        // In LOCAL mode the deploy dir = Jenkins workspace
        DEPLOY_DIR          = "${params.DEPLOY_MODE == 'local' ? env.WORKSPACE : params.SERVER_DIR}"
    }

    stages {

        // ── Stage 1: Checkout ─────────────────────────────────────────────────
        stage('Checkout') {
            steps {
                echo "📥 Checking out source..."
                checkout scm
                sh "git log --oneline -5 || true"
                echo "🚀 Deploy mode : ${params.DEPLOY_MODE}"
                echo "📁 Deploy dir  : ${env.DEPLOY_DIR}"
            }
        }

        // ── Stage 2: Sync files (REMOTE mode only) ────────────────────────────
        // LOCAL mode: Jenkins workspace already has the code — skip rsync entirely
        // REMOTE mode: rsync workspace to server
        stage('Sync to Server') {
            when {
                expression { params.DEPLOY_MODE == 'remote' }
            }
            steps {
                sshagent(credentials: [env.SSH_CRED_ID]) {
                    sh """
                        echo "📤 Syncing files to ${SERVER_USER}@${params.SERVER_HOST}:${params.SERVER_DIR} ..."
                        rsync -avz --delete \\
                            --exclude='.git' --exclude='target' \\
                            --exclude='node_modules' --exclude='.next' \\
                            --exclude='*.log' --exclude='uploads' --exclude='index_output' \\
                            -e "ssh ${SSH_OPTS}" \\
                            "\${WORKSPACE}/" \\
                            "${SERVER_USER}@${params.SERVER_HOST}:${params.SERVER_DIR}/"
                        echo "✅ Sync complete."
                    """
                }
            }
        }

        // ── Stage 3: Prepare deploy script ────────────────────────────────────
        stage('Prepare') {
            steps {
                script {
                    if (params.DEPLOY_MODE == 'local') {
                        // LOCAL: just chmod the script directly in workspace
                        sh "chmod +x ${env.WORKSPACE}/scripts/deploy-server.sh"
                        echo "✅ deploy-server.sh ready (local mode)"
                    } else {
                        // REMOTE: chmod on the server
                        sshagent(credentials: [env.SSH_CRED_ID]) {
                            sh """
                                ssh ${SSH_OPTS} ${SERVER_USER}@${params.SERVER_HOST} \\
                                    "chmod +x ${params.SERVER_DIR}/scripts/deploy-server.sh"
                            """
                        }
                        echo "✅ deploy-server.sh ready (remote mode)"
                    }
                }
            }
        }

        // ── Stage 4: DB Migrate (Flyway) ─────────────────────────────────────
        stage('DB Migrate') {
            steps {
                script {
                    def migrationDir = "${env.DEPLOY_DIR}/backend/src/main/resources/db/migration"

                    if (params.DEPLOY_MODE == 'local') {
                        // Define a shell function so the subcommand (info/migrate)
                        // is always passed as an argument — NOT appended after a newline.
                        sh """
                            set -e
                            MIGRATION_DIR="${migrationDir}"

                            flyway_run() {
                                docker run --rm --network host \\
                                    -v "\${MIGRATION_DIR}:/flyway/sql" \\
                                    flyway/flyway:9-alpine \\
                                    -url="jdbc:postgresql://localhost:5432/coursechatbot" \\
                                    -user="chatbot" \\
                                    -password="chatbot_secret" \\
                                    -locations="filesystem:/flyway/sql" \\
                                    "\$1"
                            }

                            echo "🗄️  Flyway status (before)..."
                            flyway_run info || true

                            echo "🗄️  Running Flyway migrate..."
                            flyway_run migrate

                            echo "✅ Flyway complete."
                        """
                    } else {
                        sshagent(credentials: [env.SSH_CRED_ID]) {
                            sh """
                                ssh ${SSH_OPTS} ${SERVER_USER}@${params.SERVER_HOST} bash -s << 'REMOTE'
                                    set -e
                                    MIGRATION_DIR="${params.SERVER_DIR}/backend/src/main/resources/db/migration"

                                    flyway_run() {
                                        docker run --rm --network host \\
                                            -v "\${MIGRATION_DIR}:/flyway/sql" \\
                                            flyway/flyway:9-alpine \\
                                            -url="jdbc:postgresql://localhost:5432/coursechatbot" \\
                                            -user="chatbot" \\
                                            -password="chatbot_secret" \\
                                            -locations="filesystem:/flyway/sql" \\
                                            "\$1"
                                    }

                                    echo "🗄️  Flyway status (before)..."
                                    flyway_run info || true

                                    echo "🗄️  Running Flyway migrate..."
                                    flyway_run migrate

                                    echo "✅ Flyway complete."
REMOTE
                            """
                        }
                    }
                }
            }
        }

        // ── Stage 5: Deploy services ──────────────────────────────────────────
        stage('Deploy') {
            steps {
                script {
                    def deployEnv = """SERVER_DIR=${env.DEPLOY_DIR} \\
                             COMPOSE_FILE=${COMPOSE_FILE} \\
                             BUILD_BACKEND=${params.BUILD_BACKEND} \\
                             BUILD_FRONTEND=${params.BUILD_FRONTEND} \\
                             RESET_DB=${params.RESET_DB} \\
                             OLLAMA_MODEL=${params.OLLAMA_MODEL} \\
                             NEXT_PUBLIC_API_URL=${NEXT_PUBLIC_API_URL}"""

                    if (params.DEPLOY_MODE == 'local') {
                        sh """
                            echo "🚀 Deploying locally (no SSH)..."
                            ${deployEnv} \\
                            bash ${env.WORKSPACE}/scripts/deploy-server.sh
                        """
                    } else {
                        sshagent(credentials: [env.SSH_CRED_ID]) {
                            sh """
                                echo "🚀 Deploying on ${params.SERVER_HOST} via SSH..."
                                ssh ${SSH_OPTS} ${SERVER_USER}@${params.SERVER_HOST} \\
                                    "${deployEnv} \\
                                     bash ${params.SERVER_DIR}/scripts/deploy-server.sh"
                            """
                        }
                    }
                }
            }
        }

        // ── Stage 6: Smoke Test ───────────────────────────────────────────────
        stage('Smoke Test') {
            steps {
                sh """
                    echo "🧪 Smoke testing ${params.SERVER_HOST} ..."
                    sleep 10

                    echo "--- Backend API ---"
                    curl -sf --max-time 15 \\
                        http://${params.SERVER_HOST}:8080/api/courses/courseIds \\
                        && echo "✅ Backend OK" \\
                        || echo "⚠️  Backend not responding yet (check logs)"

                    echo "--- Frontend ---"
                    curl -sf --max-time 15 \\
                        http://${params.SERVER_HOST}:3000/ \\
                        -o /dev/null -w "HTTP %{http_code}\\n" \\
                        && echo "✅ Frontend OK" \\
                        || echo "⚠️  Frontend not responding yet (check logs)"
                """
            }
        }
    }

    post {
        success {
            echo """
            ============================================================
            ✅  DEPLOY SUCCESSFUL  [${params.DEPLOY_MODE} mode]

            Backend   : http://${params.SERVER_HOST}:8080
            Frontend  : http://${params.SERVER_HOST}:3000
            Open WebUI: http://${params.SERVER_HOST}:3001
            Ollama    : http://${params.SERVER_HOST}:11434
            ============================================================
            """
        }
        failure {
            echo "❌ Pipeline FAILED"
            script {
                if (params.DEPLOY_MODE == 'local') {
                    // LOCAL: read logs directly, no SSH needed
                    sh """
                        echo "=== backend logs ==="
                        docker logs coursechatbot-backend  --tail 60 2>/dev/null || true
                        echo "=== postgres logs ==="
                        docker logs coursechatbot-postgres --tail 40 2>/dev/null || true
                        echo "=== compose status ==="
                        docker compose -f ${env.WORKSPACE}/${COMPOSE_FILE} ps 2>/dev/null || true
                    """
                } else {
                    try {
                        sshagent(credentials: [env.SSH_CRED_ID]) {
                            sh """
                                ssh ${SSH_OPTS} ${SERVER_USER}@${params.SERVER_HOST} '
                                    docker logs coursechatbot-backend  --tail 60 2>/dev/null || true
                                    docker logs coursechatbot-postgres --tail 40 2>/dev/null || true
                                    docker compose -f /home/dell/chatbot/docker-compose.server.yml ps 2>/dev/null || true
                                ' || true
                            """
                        }
                    } catch (Exception e) {
                        echo "⚠️  Could not fetch server logs: ${e.message}"
                        echo "    → Add Jenkins credential ID: server-ssh-key  (for REMOTE mode)"
                    }
                }
            }
        }
        always {
            echo "Build #${env.BUILD_NUMBER} — ${currentBuild.currentResult} — mode: ${params.DEPLOY_MODE}"
        }
    }
}
