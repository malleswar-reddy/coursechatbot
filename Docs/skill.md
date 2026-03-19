 Architecture After Migration
┌──────────────────────────────┐        ┌──────────────────────────────┐
│         Mac (Dev)            │        │    Dell Server (100.114.88.111)│
│                              │        │                              │
│  ┌──────────┐ ┌──────────┐  │  HTTP  │  ┌──────────┐               │
│  │ postgres │ │ backend  │◄─┼────────┼─►│  ollama  │ :11434        │
│  └──────────┘ └──────────┘  │        │  └──────────┘               │
│               ┌──────────┐  │        │  ┌──────────┐               │
│               │ frontend │  │        │  │open-webui│ :3001         │
│               └──────────┘  │        │  └──────────┘               │
└──────────────────────────────┘        └──────────────────────────────┘
Files Changed/Created
File
Action
Purpose
docker-compose.yml
✅ Updated
Removed ollama + open-webui, backend points to Dell
docker-compose.server.yml
✅ Created
Runs ollama + open-webui on Dell server
To Start on Mac
 ```bash
docker compose up -d
```
To Start on Dell Server (once download finishes)
 ```bash
cd /home/dell/chatbot
docker compose up -d
```
Verify Ollama is Reachable from Mac
curl http://100.114.88.111:11434
⚠️ Note: Make sure port 11434 is open in the Dell firewall: sudo ufw allow 11434



pageindex 😄 ssh dell@100.114.88.111 "cat /proc/cpuinfo | grep 'model name' | head -1 && nproc && free -h"
model name      : Intel(R) Core(TM) i5-6500 CPU @ 3.20GHz
4
total        used        free      shared  buff/cache   available
Mem:            15Gi       4.1Gi       5.1Gi        55Mi       6.3Gi        11Gi
Swap:          2.0Gi       315Mi       1.7Gi
pageindex 😄 ssh dell@100.114.88.111 "nproc && free -h && docker stats --no-stream coursechatbot-ollama"
4
total        used        free      shared  buff/cache   available
Mem:            15Gi       4.1Gi       5.1Gi        55Mi       6.3Gi        11Gi
Swap:          2.0Gi       315Mi       1.7Gi
CONTAINER ID   NAME                   CPU %     MEM USAGE / LIMIT     MEM %     NET I/O          BLOCK I/O     PIDS
a814b94302de   coursechatbot-ollama   0.00%     625.2MiB / 15.49GiB   3.94%     436kB / 1.08MB   1.11MB / 0B   18
pageindex 😄 


cd /Users/malleswar/IdeaProjects/coursechatbot

# Rebuild only backend (frontend unchanged)
docker compose up -d --build backend

# Wait 30s then test
sleep 30 && curl -s -X POST http://localhost:8080/api/chat \
-H "Content-Type: application/json" \
-d '{"courseId":"java-how-to-program","question":"Explain switch statement in Java"}' | python3 -m json.tool