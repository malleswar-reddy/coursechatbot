scp "dell@100.114.88.111:/home/dell/Desktop/Desktop/*.pdf" .

scp /Users/malleswar/IdeaProjects/coursechatbot/docker-compose.server.yml dell@100.114.88.111:/home/dell/chatbot/docker-compose.yml

docker ps --filter "name=coursechatbot" --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"


docker ps -a | grep -E "course|chatbot"

