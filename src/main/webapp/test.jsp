<%@ page contentType="text/html; charset=UTF-8" pageEncoding="UTF-8" %>
<!DOCTYPE html>
<html lang="ko">
<head>
  <meta charset="UTF-8" />
  <title>테스트 페이지</title>
  <style>
    body {
      font-family: Arial, sans-serif;
      margin: 24px;
    }
    .button-group {
      display: flex;
      gap: 12px;
      margin-bottom: 16px;
    }
    button {
      padding: 8px 14px;
      cursor: pointer;
    }
    #content {
      border: 1px solid #ddd;
      padding: 16px;
      min-height: 120px;
      background: #fafafa;
    }
  </style>
</head>
<body>
  <h1>test.jsp</h1>
  <p>아래 버튼을 클릭하면 test1.jsp, test2.jsp 내용을 가져와서 표시합니다.</p>

  <div class="button-group">
    <button type="button" data-target="test1.jsp">test1.jsp 보기</button>
    <button type="button" data-target="test2.jsp">test2.jsp 보기</button>
  </div>

  <div id="content">여기에 결과가 표시됩니다.</div>

  <script>
    const content = document.getElementById('content');
    const buttons = document.querySelectorAll('button[data-target]');

    // 버튼 클릭 시 fetch로 JSP 내용을 가져와 innerHTML에 표시
    buttons.forEach((button) => {
      button.addEventListener('click', async () => {
        const target = button.getAttribute('data-target');
        try {
          const response = await fetch(target, { cache: 'no-store' });
          if (!response.ok) {
            throw new Error('요청 실패');
          }
          const html = await response.text();
          content.innerHTML = html;
        } catch (error) {
          content.innerHTML = '<p style="color: red;">불러오기에 실패했습니다.</p>';
        }
      });
    });
  </script>
</body>
</html>
