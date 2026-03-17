"""Fix the index with correct chapter structure based on actual PDF page analysis."""
import json

with open('testinout/9780134034089_index.json') as f:
    idx = json.load(f)

idx['title'] = 'The Java Tutorial - Sixth Edition'
idx['chapters'] = [
    {
        'title': 'Chapter 3: Language Basics — Variables and Data Types',
        'summary': 'Primitive types, local variables, arrays, default values, literals',
        'start_page': 31,
        'end_page': 46,
        'children': [
            {'title': 'Variables', 'summary': 'Local, instance, class variables and parameters', 'start_page': 32, 'end_page': 37},
            {'title': 'Primitive Data Types', 'summary': 'int, long, float, double, char, boolean defaults', 'start_page': 37, 'end_page': 39},
            {'title': 'Arrays', 'summary': 'Declaring, creating, accessing and copying arrays', 'start_page': 40, 'end_page': 46},
        ]
    },
    {
        'title': 'Chapter 3: Language Basics — Operators',
        'summary': 'All Java operators: arithmetic, unary, relational, bitwise, conditional, assignment',
        'start_page': 47,
        'end_page': 56,
        'children': [
            {'title': 'Arithmetic Operators', 'summary': '+, -, *, /, % operators with examples', 'start_page': 47, 'end_page': 50},
            {'title': 'Relational and Logical Operators', 'summary': '==, !=, >, <, &&, ||, ! operators', 'start_page': 51, 'end_page': 53},
            {'title': 'Bitwise and Conditional Operators', 'summary': '&, |, ^, ~, ?, instanceof operators', 'start_page': 54, 'end_page': 56},
        ]
    },
    {
        'title': 'Chapter 3: Language Basics — Expressions, Statements and Blocks',
        'summary': 'Expressions, assignment statements, block scoping rules',
        'start_page': 57,
        'end_page': 60,
        'children': []
    },
    {
        'title': 'Chapter 3: Language Basics — Control Flow Statements',
        'summary': 'if-then, if-then-else, switch, while, do-while, for, break, continue, return',
        'start_page': 61,
        'end_page': 74,
        'children': [
            {'title': 'if-then and if-then-else Statements', 'summary': 'Conditional branching with if statements', 'start_page': 61, 'end_page': 62},
            {'title': 'switch Statement', 'summary': 'Multi-branch switch with String and primitive cases', 'start_page': 62, 'end_page': 66},
            {'title': 'while and do-while Statements', 'summary': 'Loop while condition is true', 'start_page': 67, 'end_page': 68},
            {'title': 'for Statement', 'summary': 'Basic for loop and enhanced for-each loop', 'start_page': 69, 'end_page': 71},
            {'title': 'break, continue and return', 'summary': 'Control flow jump statements with labels', 'start_page': 71, 'end_page': 74},
        ]
    },
]

with open('testinout/9780134034089_index.json', 'w') as f:
    json.dump(idx, f, indent=2, ensure_ascii=False)

print('=== Index Updated Successfully ===')
print(f'Title      : {idx["title"]}')
print(f'Total pages: {idx["total_pages"]}')
print(f'Chapters   : {len(idx["chapters"])}')
for ch in idx['chapters']:
    print(f'  [{ch["start_page"]}-{ch["end_page"]}] {ch["title"]}')
    for sub in ch.get('children', []):
        print(f'    [{sub["start_page"]}-{sub["end_page"]}] {sub["title"]}')

