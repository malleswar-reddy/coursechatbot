print("Hello, World!")

is_connected = False

if is_connected:
    print("The chatbot is online! 🤖")
else:
    print("Connection failed. Check your internet.")

def greet_user(name):
    return f"Hello, {name}! Welcome to the chatbot."
user_name = "Alice"
greeting_message = greet_user(user_name)
print(greeting_message)

users = ["Alice", "Bob", "Charlie"]
for user in users:
    print(f"Hello, {user}!")

for i in range(10):
    print(f" Counting : {i+1}")


messages = [
    {"text": "Hello!", "sent": True},
    {"text": "How are you?", "sent": False},
    {"text": "Goodbye!", "sent": True}
]

for msg in messages:
    if msg["sent"]:
        print(f"Delivered: {msg['text']}")
    else:
        print(f"Failed to send: {msg['text']}")
