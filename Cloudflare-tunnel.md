This is your **Executive Command Guide**. You can save this as `CLOUDFLARE_TERMINAL_OPS.md`. It combines the configuration logic with the exact terminal commands you need to run on your **Dell OptiPlex 5050**.

---

# 🛠 Cloudflare Tunnel: Terminal Operations Guide

### 1. Initial Directory & Permission Setup
Before running the container, ensure the folder exists and the Docker user (`nonroot` UID `65532`) can read your keys.

```bash
# Create the directory if not already there
sudo mkdir -p /etc/cloudflared/

# Set ownership so the Docker container can read the JSON and Cert files
sudo chown -R 65532:65532 /etc/cloudflared/

# Verify permissions (should show 65532)
ls -asl /etc/cloudflared/
```

---

### 2. The Master Configuration (`config.yml`)
Use this command to open and edit your routing rules:
```bash
sudo nano /etc/cloudflared/config.yml
```
**Paste this content (Current Stack):**
```yaml
tunnel: 6dc33aca-58d8-4c98-94a5-91be75c6cd2c
credentials-file: /home/nonroot/.cloudflared/6dc33aca-58d8-4c98-94a5-91be75c6cd2c.json

ingress:
  - hostname: hirevitae.chakritech.org
    service: http://172.17.0.1:4000
  - hostname: jenkins-hirevitae.chakritech.org
    service: http://172.17.0.1:8080
  - hostname: phpadmin-hirevitae.chakritech.org
    service: http://172.17.0.1:8081
  - hostname: hirevitae-chatbot.chakritech.org
    service: http://172.17.0.1:3000
  - hostname: hirevitae-chatbotapi.chakritech.org
    service: http://172.17.0.1:8000
  - service: http_status:404
```
*(Press `Ctrl+O`, `Enter`, then `Ctrl+X` to save and exit)*

---

### 3. DNS Routing Commands
You must run these **once** for every new hostname you add. This creates the "CNAME" record in your Cloudflare dashboard automatically.

```bash
# Main App
cloudflared tunnel route dns 6dc33aca-58d8-4c98-94a5-91be75c6cd2c hirevitae.chakritech.org

# Jenkins & phpAdmin
cloudflared tunnel route dns 6dc33aca-58d8-4c98-94a5-91be75c6cd2c jenkins-hirevitae.chakritech.org
cloudflared tunnel route dns 6dc33aca-58d8-4c98-94a5-91be75c6cd2c phpadmin-hirevitae.chakritech.org

# Chatbot Stack
cloudflared tunnel route dns 6dc33aca-58d8-4c98-94a5-91be75c6cd2c hirevitae-chatbot.chakritech.org
cloudflared tunnel route dns 6dc33aca-58d8-4c98-94a5-91be75c6cd2c hirevitae-chatbotapi.chakritech.org
```

---

### 4. Docker Deployment Commands
Use these commands to manage the lifecycle of your tunnel container.

**A. Start the Tunnel (First time):**
```bash
docker run -d --name cloudflare-tunnel \
  --restart always \
  -v /etc/cloudflared:/home/nonroot/.cloudflared \
  cloudflare/cloudflared:latest \
  tunnel --config /home/nonroot/.cloudflared/config.yml run
```

**B. Apply Changes (After editing config.yml):**
```bash
docker restart cloudflare-tunnel
```

**C. Check if it's Online:**
```bash
docker ps | grep cloudflare
```

---

### 5. Troubleshooting & Debugging
If a website isn't loading, run these commands in order:

**Check the "Heartbeat":**
Check the logs to see if the tunnel is connected to the Bengaluru or Mumbai data centers.
```bash
docker logs -f cloudflare-tunnel
```

**Check Local Ports:**
Verify that your Jenkins/Next.js apps are actually running on the ports you assigned.
```bash
# Check if port 8080 or 4000 is active
sudo ss -tulpn | grep -E '8080|4000|8081|3000|8000'
```

**Force a Clean Start:**
If the container is acting weird, delete and recreate it:
```bash
docker rm -f cloudflare-tunnel && \
docker run -d --name cloudflare-tunnel --restart always -v /etc/cloudflared:/home/nonroot/.cloudflared cloudflare/cloudflared:latest tunnel --config /home/nonroot/.cloudflared/config.yml run
```

---

### Summary Table for Reference

| Hostname | Local Destination | Command to Route |
| :--- | :--- | :--- |
| `hirevitae.chakritech.org` | `localhost:4000` | `cloudflared tunnel route dns ...` |
| `jenkins-hirevitae.chakritech.org` | `localhost:8080` | `cloudflared tunnel route dns ...` |
| `phpadmin-hirevitae.chakritech.org` | `localhost:8081` | `cloudflared tunnel route dns ...` |

**Would you like me to help you set up a simple Bash script that automates the "Add New Service" process so you don't have to type these commands manually every time?**