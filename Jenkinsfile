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
        booleanParam(
            name: 'SKIP_DB_MIGRATE',
            defaultValue: true,
            description: '⏩  Skip Flyway DB migration (set true when PostgreSQL is not used)'
        )
        string(
            name: 'OLLAMA_MODEL',
            defaultValue: 'gemma3:4b',
            description: '🤖  Ollama model to use'
        )
        string(
            name: 'SERVER_HOST',
            defaultValue: '100.114.88.111',
            description: '🌐  Server IP (used for smoke test URLs and REMOTE mode SSH)'
        )
        string(
            name: 'SERVER_DIR',
            defaultValue: '/home/dell/chatbot/workspace',
            description: '📁  Project dir on server (REMOTE mode only — LOCAL uses Jenkins workspace)'
        )
        string(
            name: 'NEXT_PUBLIC_API_URL',
            defaultValue: 'https://hirevitae-chatbotapi.chakritech.org',
            description: '🌐  Public API URL baked into the frontend bundle (must be HTTPS when served via tunnel)'
        )
    }

    environment {
        SERVER_USER         = 'dell'
        SSH_CRED_ID         = 'server-ssh-key'
        SSH_OPTS            = '-o StrictHostKeyChecking=no -o ConnectTimeout=30'
        COMPOSE_FILE        = 'docker-compose.server.yml'
        NEXT_PUBLIC_API_URL = "${params.NEXT_PUBLIC_API_URL}"
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

        // ── Stage 1b: Init Workspace dir ─────────────────────────────────────
        // Create (and empty) SERVER_DIR before any files are synced into it.
        stage('Init Workspace') {
            steps {
                script {
                    if (params.DEPLOY_MODE == 'local') {
                        sh """
                            echo "🗂️  Creating empty workspace dir: ${params.SERVER_DIR} ..."
                            rm -rf "${params.SERVER_DIR}"
                            mkdir -p "${params.SERVER_DIR}"
                            echo "✅ ${params.SERVER_DIR} is ready (empty)"
                        """
                    } else {
                        sshagent(credentials: [env.SSH_CRED_ID]) {
                            sh """
                                echo "🗂️  Creating empty workspace dir on ${params.SERVER_HOST}: ${params.SERVER_DIR} ..."
                                ssh ${SSH_OPTS} ${SERVER_USER}@${params.SERVER_HOST} \\
                                    "rm -rf '${params.SERVER_DIR}' && mkdir -p '${params.SERVER_DIR}'"
                                echo "✅ ${params.SERVER_DIR} is ready (empty)"
                            """
                        }
                    }
                }
            }
        }

        // ── Stage 2: Sync files ───────────────────────────────────────────────
        // LOCAL mode: rsync workspace → SERVER_DIR so manual commands work there too
        // REMOTE mode: rsync workspace → remote server via SSH
        stage('Sync to Server') {
            steps {
                script {
                    if (params.DEPLOY_MODE == 'local') {
                        sh """
                            echo "📂 Syncing workspace → ${params.SERVER_DIR} ..."
                            mkdir -p ${params.SERVER_DIR} 2>/dev/null || true
                            rsync -a --delete \\
                                --exclude='.git' --exclude='target' \\
                                --exclude='node_modules' --exclude='.next' \\
                                --exclude='*.log' --exclude='uploads' --exclude='index_output' \\
                                "${env.WORKSPACE}/" \\
                                "${params.SERVER_DIR}/" 2>/dev/null \\
                            && echo "✅ Synced to ${params.SERVER_DIR}" \\
                            || echo "⚠️  Could not sync to ${params.SERVER_DIR} (permission) — deploying from workspace only (OK)"
                        """
                    } else {
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

        // ── Stage 4: DB Migrate (Flyway) — skipped when SKIP_DB_MIGRATE=true ──
        stage('DB Migrate') {
            when {
                expression { !params.SKIP_DB_MIGRATE }
            }
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
                    echo "🧪 Smoke testing ..."
                    sleep 30

                    echo "--- Backend (direct) ---"
                    curl -sf --max-time 20 \\
                        http://${params.SERVER_HOST}:8000/api/courses/courseIds \\
                        && echo "✅ Backend direct OK" \\
                        || echo "⚠️  Backend direct not responding yet (check logs)"

                    echo "--- Backend API (tunnel) ---"
                    curl -sf --max-time 20 \\
                        ${params.NEXT_PUBLIC_API_URL}/api/courses/courseIds \\
                        && echo "✅ Backend tunnel OK" \\
                        || echo "⚠️  Backend tunnel not responding yet (Cloudflare may need more time)"

                    echo "--- ChromaDB health (direct) ---"
                    curl -sf --max-time 10 \\
                        http://${params.SERVER_HOST}:8001/api/v2/heartbeat \\
                        && echo "✅ ChromaDB OK" \\
                        || echo "⚠️  ChromaDB not responding (check container logs)"

                    echo "--- Frontend (direct) ---"
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

            Frontend  (tunnel) : https://hirevitae-chatbot.chakritech.org
            Backend   (tunnel) : ${params.NEXT_PUBLIC_API_URL}
            Backend   (direct) : http://${params.SERVER_HOST}:8000
            Frontend  (direct) : http://${params.SERVER_HOST}:3000
            ChromaDB           : http://${params.SERVER_HOST}:8001
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
