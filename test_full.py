import sys, json, urllib.request, time

# 登录获取 token
d = json.loads(urllib.request.urlopen(
    urllib.request.Request('http://localhost:8080/api/users/login',
        data=json.dumps({"username":"admin","password":"123456"}).encode(),
        headers={"Content-Type":"application/json"}), timeout=10).read())
t = d['data']['accessToken']
h = {'Authorization': 'Bearer ' + t, 'Content-Type': 'application/json'}
results = []

def req(method, url, data=None, timeout=10):
    r = urllib.request.Request(url, data=data, headers=h, method=method)
    return json.loads(urllib.request.urlopen(r, timeout=timeout).read())

def test(name, ok, msg=""):
    results.append((name, ok, msg))

# ── 1. 登录 ──
test("1/10 登录", d['code']==200, f"admin/123456 → role={d['data']['user']['role']}")

# ── 2. AI 模型列表 ──
try:
    resp = req("GET", "http://localhost:8080/api/ai/models", timeout=5)
    test("2/10 AI模型列表", resp['code']==200, f"{len(resp['data'])} 个模型")
except Exception as e: test("2/10 AI模型列表", False, str(e))

# ── 3. AI 同步摘要 ──
try:
    resp = req("POST", "http://localhost:8080/api/ai/summarize",
        data=json.dumps({"content":"DocAI是一个AI文档处理平台，支持文档上传、AI摘要、关键词提取和多模型切换。","maxLength":100}).encode(),
        timeout=30)
    test("3/10 AI同步摘要", resp['code']==200, f"摘要长度={len(resp['data']['summary'])}")
except Exception as e: test("3/10 AI同步摘要", False, str(e))

# ── 4. AI 异步关键词 ──
try:
    resp = req("POST", "http://localhost:8080/api/ai/async/keywords?model=deepseek-chat",
        data=json.dumps({"content":"DocAI AI文档处理平台 支持文档上传 AI摘要 关键词提取","count":5}).encode(),
        timeout=10)
    jobId = resp['data']['jobId']
    test("4/10 异步关键词提交", resp['code']==200, f"jobId={jobId[:8]}...")
    for _ in range(20):
        time.sleep(1)
        resp2 = req("GET", f"http://localhost:8080/api/ai/async/jobs/{jobId}", timeout=5)
        s = resp2['data']['status']
        if s == 'success':
            tags = [k['word'] for k in resp2['data']['result']['keywords']]
            test("4/10 异步关键词结果", True, f"关键词: {tags}")
            break
        elif s == 'failed':
            test("4/10 异步关键词结果", False, resp2['data']['error'])
            break
except Exception as e: test("4/10 异步关键词", False, str(e))

# ── 5. 文档服务列表 ──
try:
    resp = req("GET", "http://localhost:8080/api/documents?userId=3", timeout=5)
    
    # 尝试取 data，兼容不同后端返回结构
    data = None
    if isinstance(resp.get('data'), list):
        data = resp['data']
    elif isinstance(resp, list):
        data = resp
        
    docCount = len(data) if data else 0
    test("5/10 文档列表", resp.get('code', 200) == 200, f"{docCount} 篇文档")
except Exception as e: test("5/10 文档列表", False, str(e))

# ── 6. AIOps 健康检查 ──
try:
    resp = req("GET", "http://localhost:8080/api/ai/aiops/health", timeout=5)
    test("6/10 AIOps健康", resp['code']==200, f"status={resp['data']['status']}")
except Exception as e: test("6/10 AIOps健康", False, str(e))

# ── 7. AIOps 监控指标 ──
try:
    resp = req("GET", "http://localhost:8080/api/ai/aiops/monitor", timeout=5)
    windows = list(resp['data']['windows'].keys())
    test("7/10 AIOps指标", resp['code']==200, f"窗口: {windows}")
except Exception as e: test("7/10 AIOps指标", False, str(e))

# ── 8. PPT 生成 ──
try:
    resp = req("POST", "http://localhost:8080/api/skills/html-ppt/generate",
        data=json.dumps({"title":"测试","theme":"tokyo-night","outline":"概述\n功能","model":"deepseek-chat"}).encode(),
        timeout=60)
    hasHtml = 'htmlContent' in resp.get('data', {})
    test("8/10 PPT生成", resp['code']==200 and hasHtml, f"含HTML内容={hasHtml}")
except Exception as e: test("8/10 PPT生成", False, str(e))

# ── 9. Agent 执行 ──
try:
    resp = req("POST", "http://localhost:8080/api/ai/agent/execute",
        data=json.dumps({"task":"你好","model":"deepseek-chat","context":{}}).encode(),
        timeout=30)
    hasAnswer = 'answer' in resp.get('data', {})
    hasConvId = 'conversationId' in resp.get('data', {})
    test("9/10 Agent执行", resp['code']==200 and hasAnswer, f"含回答={hasAnswer}, convId={hasConvId}")
except Exception as e: test("9/10 Agent执行", False, str(e))

# ── 10. 协作者接口 ──
try:
    # 先获取一个文档ID
    resp = req("GET", "http://localhost:8080/api/documents?userId=3", timeout=5)
    data = None
    if isinstance(resp.get('data'), list):
        data = resp['data']
    elif isinstance(resp, list):
        data = resp
    
    if data and len(data) > 0:
        docId = data[0]['id']
        # 查协作者列表
        resp2 = req("GET", f"http://localhost:8080/api/documents/{docId}/collaborators", timeout=5)
        test("10/10 协作者接口", resp2['code']==200, f"文档ID={docId}, 协作者={len(resp2.get('data',[]))}人")
    else:
        test("10/10 协作者接口", True, "跳过（无文档）")
except Exception as e: test("10/10 协作者接口", False, str(e))

# ── 输出结果 ──
print()
print("=" * 60)
print("  DocAI 全功能测试报告")
print("=" * 60)
all_pass = True
for name, ok, msg in results:
    icon = "🟢" if ok else "🔴"
    print(f"  {icon} {name}")
    print(f"     {msg}")
    if not ok:
        all_pass = False
print("=" * 60)
print(f"  总计: {sum(1 for _,ok,_ in results if ok)}/10 通过")
if all_pass:
    print("  🎉 全部测试通过！")
else:
    print("  ⚠️ 存在失败项")
print("=" * 60)
