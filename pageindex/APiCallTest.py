import requests
import random as rnd
from datetime import datetime

# 1. Define the API URL
url = "https://jsonplaceholder.typicode.com/todos"

try:
    # 2. Get the data from the API
    response = requests.get(url)

    # 3. Convert the response to JSON (Python list/dictionary)
    data = response.json()

    # 4. Use a for loop to print specific parts of the JSON
    print(f"--- Printing first 5 items ---")

    for item in data[:5]:  # data[:5] limits it to the first 5 items
        status = "✅" if item["completed"] else "❌"
        print(f"{status} {item['title']}")

except Exception as e:
    print(f"An error occurred: {e}")


random_number = rnd.randint(1, 100)
print(f"Generated random number: {random_number}")

date = datetime.now()
print(f"Current date and time: {date}")