#!/usr/bin/env python3
"""Prepare synthetic OpenRouter requests; paid execution requires an explicit CLI flag.
No .env loading, no key logging. This is not part of Gradle/JVM/Android test tasks.
"""
import argparse
import json
import os
from pathlib import Path
import re
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output', type=Path, default=Path('/tmp/cramin-topic-quality'))
parser.add_argument('--execute-paid', action='store_true', help='Explicitly run paid requests using OPENROUTER_API_KEY from the environment')
args = parser.parse_args()
source = (ROOT / 'app/src/main/kotlin/pro/perfectproduct/cramin/llm/TopicCategories.kt').read_text()
rules = re.search(r'val rules = """(.*?)"""\.trimIndent', source, re.S).group(1).strip()
suffix = re.search(r'val prompt = rules \+ """(.*?)"""', source, re.S).group(1)
schema = json.loads(re.search(r'val schema = Json.parseToJsonElement\("""(.*?)"""', source, re.S).group(1))
role = json.loads((ROOT / 'config/models.json').read_text())['roles']['topic']
fixtures = json.loads((ROOT / 'docs/topic-categories/quality-cases.json').read_text())
args.output.mkdir(parents=True, exist_ok=True)
key = os.environ.get('OPENROUTER_API_KEY') if args.execute_paid else None
if args.execute_paid and not key:
    parser.error('OPENROUTER_API_KEY is required for explicit paid execution')
results = []
for document in fixtures['documents']:
    items = document['items']
    variants = [('original', items), ('reversed', list(reversed(items))), ('even', items[::2]), ('odd', items[1::2])]
    for variant, group in variants:
        supplied = [{k: v for k, v in item.items() if k != 'expected'} for item in group]
        request = {'model': role['model'], 'reasoning': role['reasoning'], 'provider': {'require_parameters': True},
                   'messages': [{'role': 'system', 'content': rules + suffix}, {'role': 'user', 'content': json.dumps({'document': document['document'], 'items': supplied}, ensure_ascii=False)}],
                   'temperature': role['temperature'], 'max_tokens': 512 + sum(48 + len(i['id'].encode()) for i in supplied),
                   'response_format': {'type': 'json_schema', 'json_schema': {'name': 'card_topic_categories', 'strict': True, 'schema': schema}}}
        stem = f"{document['id']}-{variant}"
        (args.output / f'{stem}-request.json').write_text(json.dumps(request, ensure_ascii=False, indent=2))
        if key:
            req = urllib.request.Request('https://openrouter.ai/api/v1/chat/completions', data=json.dumps(request).encode(),
                                         headers={'Authorization': f'Bearer {key}', 'Content-Type': 'application/json'}, method='POST')
            try:
                with urllib.request.urlopen(req, timeout=180) as response:
                    payload = json.load(response)
            except Exception as error:
                raise SystemExit(f'Request failed: {type(error).__name__}; no response body or credentials logged') from None
            choice = payload['choices'][0]
            if choice.get('finish_reason') != 'stop' or choice['message'].get('refusal'):
                raise SystemExit(f'{stem}: unfinished/refused response; not scored')
            rows = json.loads(choice['message']['content'])['items']
            expected = {i['id']: i['expected'] for i in group}
            actual = {i['id']: i['category'] for i in rows}
            if len(rows) != len(actual) or actual.keys() != expected.keys() or any(v not in ('CORE','RELATED','GENERAL') for v in actual.values()):
                raise SystemExit(f'{stem}: invalid output; not scored')
            (args.output / f'{stem}-response.json').write_text(json.dumps(payload, ensure_ascii=False, indent=2))
            results.append({'variant': stem, 'matches': sum(actual[k] == v for k, v in expected.items()), 'total': len(expected), 'actual': actual})
if key:
    (args.output / 'scores.json').write_text(json.dumps(results, ensure_ascii=False, indent=2))
    print('Paid evaluation complete. Compare scores and original/reversed/even/odd assignments; manual labels are provisional.')
else:
    print('Prepared 12 synthetic requests. No API requests made. Paid execution requires --execute-paid and an environment key.')
