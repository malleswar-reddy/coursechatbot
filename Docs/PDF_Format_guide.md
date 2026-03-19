# 📘 PageIndex Project — PDF Format Guide for Java Course

## 🔍 How the System Works

```
PDF File
   │
   ▼
build_index.py  ──► Extracts all pages ──► Asks LLM to build Table of Contents (JSON)
   │
   ▼
course_index.json  (hierarchical TOC + all page texts)
   │
   ▼
ingest_to_db.py  ──► Saves to PostgreSQL (course_index + course_content tables)
   │
   ▼
query_index.py   ──► Student asks question ──► LLM picks page range ──► LLM answers
```

---

## ✅ What the PDF MUST Contain

### 1. 📋 Clear Chapter / Section Titles
The LLM reads page text and tries to detect structure.
Your PDF headings should be **clear and descriptive**:

```
✅ GOOD:
  Chapter 1: Introduction to Java
  Chapter 2: Object-Oriented Programming
  2.1 Classes and Objects
  2.2 Inheritance and Polymorphism

❌ BAD:
  Chapter 1
  Topic A
  (no titles at all)
```

---

### 2. 📄 Readable Text Content (NOT scanned images)
The system uses `pdfplumber` to extract text.

```
✅ GOOD: Digitally created PDF (Word, LaTeX, Google Docs exported to PDF)
❌ BAD:  Scanned/photographed book pages (text extraction fails = empty pages)
```

> If your PDF is a scanned book, you need OCR first:
> ```bash
> pip install ocrmypdf
> ocrmypdf input_scan.pdf output_readable.pdf
> ```

---

### 3. 🗂️ Recommended PDF Structure for a Java Course

```
Page 1:   Title Page
            "Complete Java Programming Course"
            Author: XYZ | 2024

Page 2:   Table of Contents (optional but helps LLM)
            Chapter 1 .............. 3
            Chapter 2 .............. 15

Page 3-14:  Chapter 1: Introduction to Java
              1.1 What is Java?
              1.2 JDK, JRE, JVM
              1.3 Hello World Program
              1.4 Data Types & Variables

Page 15-30: Chapter 2: Object-Oriented Programming
              2.1 Classes and Objects
              2.2 Constructors
              2.3 Inheritance
              2.4 Polymorphism
              2.5 Encapsulation
              2.6 Abstraction

Page 31-45: Chapter 3: Collections Framework
              3.1 List, Set, Map
              3.2 ArrayList vs LinkedList
              3.3 HashMap

Page 46-60: Chapter 4: Exception Handling
              4.1 try-catch-finally
              4.2 Custom Exceptions
              4.3 Checked vs Unchecked

Page 61-75: Chapter 5: Multithreading
              5.1 Thread class
              5.2 Runnable interface
              5.3 Synchronization

Page 76-90: Chapter 6: Java 8+ Features
              6.1 Lambda Expressions
              6.2 Stream API
              6.3 Optional class
```

---

### 4. 📝 Content Quality Per Page
Each page should have **meaningful text** (the system sends first 800 chars of each page to LLM):

```
✅ GOOD Page Content:
  Chapter 1: Introduction to Java
  
  Java is a high-level, class-based, object-oriented programming language
  designed to have as few implementation dependencies as possible.
  
  1.1 JVM (Java Virtual Machine)
  JVM is an abstract machine that enables Java programs to run on any device...

❌ BAD Page Content:
  [Just a diagram/image with no text]
  [Only page number]
  [Table with no labels]
```

---

## 🚀 How BA Gives PDF — Full Workflow

### Step 1: BA Provides the Java Course PDF
```
java_course.pdf  (100+ pages, text-based)
```

### Step 2: Build the Index
```bash
cd pageindex
pip3 install -r requirements.txt

python3 build_index.py \
  --pdf java_course.pdf \
  --output java_course_index.json \
  --ollama-url http://100.114.88.111:11434 \
  --model qwen2.5:7b
```

### Step 3: Ingest into Database
```bash
python3 ingest_to_db.py \
  --index java_course_index.json \
  --course-id java-fundamentals \
  --db-url postgresql://chatbot:chatbot_secret@localhost:5432/coursechatbot
```

### Step 4: Test a Question
```bash
python3 query_index.py \
  --index java_course_index.json \
  --question "What is the difference between ArrayList and LinkedList?" \
  --ollama-url http://100.114.88.111:11434 \
  --model qwen2.5:7b
```

---

## ⚠️ PDF Checklist Before Giving to BA

| Check | Requirement |
|---|---|
| ✅ Text extractable? | Open PDF, can you select/copy text? |
| ✅ Has chapter titles? | Each chapter clearly labeled |
| ✅ Has section headings? | Subsections like 1.1, 1.2 etc. |
| ✅ Pages have content? | No blank or image-only pages |
| ✅ File size reasonable? | Under 50MB recommended |
| ✅ Language: English? | LLM works best in English |

---

## 📦 What the System Stores in Database

### `course_index` table
```json
{
  "title": "Complete Java Programming Course",
  "chapters": [
    {
      "title": "Chapter 1: Introduction to Java",
      "summary": "Covers Java basics, JVM, JDK, and first program",
      "start_page": 3,
      "end_page": 14,
      "children": [
        { "title": "1.1 What is Java?", "start_page": 3, "end_page": 5 },
        { "title": "1.2 JVM Architecture", "start_page": 6, "end_page": 9 }
      ]
    }
  ]
}
```

### `course_content` table
```
course_id       | page_number | content
java-fundamentals|      3     | "Java is a high-level language..."
java-fundamentals|      4     | "JVM stands for Java Virtual Machine..."
```

---

## 💡 Example Student Questions the System Can Answer

Once the Java course PDF is ingested:

- *"What is the difference between interface and abstract class?"*
- *"How does garbage collection work in Java?"*
- *"Explain HashMap internal working"*
- *"What are lambda expressions?"*
- *"How to handle NullPointerException?"*
