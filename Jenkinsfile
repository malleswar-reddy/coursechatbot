// =============================================================================
// Jenkinsfile — CourseChatbot CI/CD Pipeline
// Syncs code to remote server and deploys all services via docker compose
// =============================================================================
//
// Prerequisites (configure once in Jenkins):
//   1. SSH credential:  Manage Jenkins → Credentials → Add
//                       Kind: "SSH Username with private key"
//                       ID:   server-ssh-key
//                       User: dell
//                       Key:  paste your private key (~/.ssh/id_rsa)
//
//   2. Plugins required:
//       - SSH Agent Plugin  (for sshagent{})
//       - Git Plugin        (for checkout scm)
//
//   3. Jenkins agent must have:
//       - rsync  installed  (brew install rsync  on Mac / apt install rsync on Linux)
//       - ssh    installed  (usually pre-installed)
// =============================================================================

pipeline {
    agent any

    // ── Parameters (shown as form fields when you click "Build with Parameters") ──
    parameters {
        booleanParam(
            name: 'BUILD_BACKEND',
            defaultValue: true,
            description: '🏗️  Rebuild the Spring Boot backend Docker image on server'
        )
        booleanParam(
            name: 'BUILD_FRONTEND',
            defaultValue: true,
            description: '🎨  Rebuild the Next.js frontend Docker image on server'
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
            description: '🌐  Target server IP or hostname'
        )
        string(
            name: 'SERVER_DIR',
            defaultValue: '/home/dell/chatbot',
            description: '📁  Directory on server that holds docker-compose.server.yml'
        )
    }

    // ── Fixed environment variables ───────────────────────────────────────────
    environment {
        SERVER_USER       = 'dell'
        SSH_CRED_ID       = 'server-ssh-key'          // Jenkins credential ID
        COMPOSE_FILE      = 'docker-compose.server.yml'
        NEXT_PUBLIC_API_URL = "http://${params.SERVER_HOST}:8080"
        SSH_OPTS          = '-o StrictHostKeyChecking=no -o ConnectTimeout=30'
    }

    stages {

        // ── Stage 1: Checkout source ──────────────────────────────────────────
        stage('Checkout') {
            steps {
                echo "📥 Checking out source..."
                checkout scm
                sh "git log --oneline -5 || true"
            }
        }

        // ── Stage 2: Sync project files to server via rsync ───────────────────
        stage('Sync to Server') {
            steps {
                sshagent(credentials: [env.SSH_CRED_ID]) {
                    sh """
                        echo "📤 Syncing files to ${SERVER_USER}@${params.SERVER_HOST}:${params.SERVER_DIR} ..."
                        rsync -avz --delete \\
                            --exclude='.git' \\
                            --exclude='target' \\
                            --exclude='node_modules' \\
                            --exclude='.next' \\
                            --exclude='*.log' \\
                            --exclude='uploads' \\
                            --exclude='index_output' \\
                            -e "ssh ${SSH_OPTS}" \\
                            "\${WORKSPACE}/" \\
                            "${SERVER_USER}@${params.SERVER_HOST}:${params.SERVER_DIR}/"
                        echo "✅ Sync complete."
                    """
                }
            }
        }

        // ── Stage 3: Make deploy script executable on server ─────────────────
        stage('Prepare Server') {
            steps {
                sshagent(credentials: [env.SSH_CRED_ID]) {
                    sh """
                        ssh ${SSH_OPTS} ${SERVER_USER}@${params.SERVER_HOST} \\
                            "chmod +x ${params.SERVER_DIR}/scripts/deploy-server.sh"
                    """
                }
            }
        }

        // ── Stage 4: Run Flyway DB migrations on server ───────────────────────
        //
        //  Runs BEFORE the backend starts so the schema is always up to date.
        //  Uses the official flyway/flyway Docker image (no Java/Maven needed).
        //  Reads V*.sql files from backend/src/main/resources/db/migration/
        //  (already rsync'd to server in Stage 2 above).
        // ─────────────────────────────────────────────────────────────────────
        stage('DB Migrate') {
            steps {
                sshagent(credentials: [env.SSH_CRED_ID]) {
                    sh """
                        echo "🗄️  Running Flyway migrations on server..."
                        ssh ${SSH_OPTS} ${SERVER_USER}@${params.SERVER_HOST} bash << 'REMOTE'
                            MIGRATION_DIR="${params.SERVER_DIR}/backend/src/main/resources/db/migration"

                            # Show current status
                            echo "--- Migration status BEFORE ---"
                            docker run --rm \
                                --network host \
                                -v "\${MIGRATION_DIR}:/flyway/sql" \
                                flyway/flyway:9-alpine \
                                -url="jdbc:postgresql://localhost:5432/coursechatbot" \
                                -user="chatbot" \
                                -password="chatbot_secret" \
                                -locations="filesystem:/flyway/sql" \
                                info

                            # Apply pending migrations
                            echo "--- Applying migrations ---"
                            docker run --rm \
                                --network host \
                                -v "\${MIGRATION_DIR}:/flyway/sql" \
                                flyway/flyway:9-alpine \
                                -url="jdbc:postgresql://localhost:5432/coursechatbot" \
                                -user="chatbot" \
                                -password="chatbot_secret" \
                                -locations="filesystem:/flyway/sql" \
                                migrate

                            echo "✅ Flyway migrate complete."
REMOTE
                    """
                }
            }
        }

        // ── Stage 5: Run deploy script on server ──────────────────────────────
        stage('Deploy on Server') {
            steps {
                sshagent(credentials: [env.SSH_CRED_ID]) {
                    sh """
                        echo "🚀 Running deploy-server.sh on ${params.SERVER_HOST} ..."
                        ssh ${SSH_OPTS} ${SERVER_USER}@${params.SERVER_HOST} \\
                            "SERVER_DIR=${params.SERVER_DIR} \\
                             COMPOSE_FILE=${COMPOSE_FILE} \\
                             BUILD_BACKEND=${params.BUILD_BACKEND} \\
                             BUILD_FRONTEND=${params.BUILD_FRONTEND} \\
                             RESET_DB=${params.RESET_DB} \\
                             OLLAMA_MODEL=${params.OLLAMA_MODEL} \\
                             NEXT_PUBLIC_API_URL=${NEXT_PUBLIC_API_URL} \\
                             bash ${params.SERVER_DIR}/scripts/deploy-server.sh"
                    """
                }
            }
        }

        // ── Stage 5: Smoke test from Jenkins ─────────────────────────────────
        stage('Smoke Test') {
            steps {
                sh """
                    echo "🧪 Smoke testing ${params.SERVER_HOST} ..."
                    sleep 5

                    echo ""
                    echo "--- Backend API ---"
                    curl -sf --max-time 10 \\
                        http://${params.SERVER_HOST}:8080/api/courses/courseIds \\
                        && echo "✅ /api/courses/courseIds OK" \\
                        || echo "⚠️  Backend not responding (check server logs)"

                    echo ""
                    echo "--- Frontend ---"
                    curl -sf --max-time 10 \\
                        http://${params.SERVER_HOST}:3000/ \\
                        -o /dev/null -w "HTTP %{http_code}\\n" \\
                        && echo "✅ Frontend OK" \\
                        || echo "⚠️  Frontend not responding (check server logs)"
                """
            }
        }
    }

    // ── Post actions ──────────────────────────────────────────────────────────
    post {
        success {
            echo """
            ============================================================
            ✅  DEPLOY SUCCESSFUL

            Server      : ${params.SERVER_HOST}
            Backend     : http://${params.SERVER_HOST}:8080
            Frontend    : http://${params.SERVER_HOST}:3000
            Open WebUI  : http://${params.SERVER_HOST}:3001
            Ollama      : http://${params.SERVER_HOST}:11434
            DB          : ${params.SERVER_HOST}:5432  db=coursechatbot user=chatbot
            ============================================================
            """
        }
        failure {
            echo "❌ Pipeline FAILED — fetching server logs..."
            sshagent(credentials: [env.SSH_CRED_ID]) {
                sh """
                    ssh ${SSH_OPTS} ${SERVER_USER}@${params.SERVER_HOST} '
                        echo "=== backend logs ==="
                        docker logs coursechatbot-backend  --tail 60 2>/dev/null || true
                        echo "=== postgres logs ==="
                        docker logs coursechatbot-postgres --tail 40 2>/dev/null || true
                        echo "=== frontend logs ==="
                        docker logs coursechatbot-frontend --tail 30 2>/dev/null || true
                        echo "=== compose status ==="
                        docker compose -f /home/dell/chatbot/docker-compose.server.yml ps 2>/dev/null || true
                    ' || true
                """
            }
        }
        always {
            echo "Build #${env.BUILD_NUMBER} finished — ${currentBuild.currentResult}"
        }
    }
}

