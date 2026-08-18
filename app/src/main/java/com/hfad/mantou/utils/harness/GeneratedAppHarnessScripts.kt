package com.hfad.mantou.utils.harness

object GeneratedAppHarnessScripts {

    val selfTest: String = """
        return (async function () {
          var cases = [];
          var body = document.body;
          var interactiveElements = Array.from(document.querySelectorAll('button, input, textarea, select, [role="button"], a[href]'));
          var visibleInteractiveElements = interactiveElements.filter(function (element) {
            var rect = element.getBoundingClientRect();
            var style = window.getComputedStyle(element);
            return rect.width > 0 && rect.height > 0 && style.display !== 'none' && style.visibility !== 'hidden';
          });
          cases.push({
            name: 'document-ready',
            passed: document.readyState === 'interactive' || document.readyState === 'complete',
            message: '文档尚未完成加载'
          });
          cases.push({
            name: 'body-rendered',
            passed: !!body && body.getBoundingClientRect().height > 0,
            message: '页面主体没有可见高度'
          });
          cases.push({
            name: 'content-present',
            passed: !!body && body.textContent.trim().length > 0,
            message: '页面没有可见内容'
          });
          cases.push({
            name: 'primary-interaction-present',
            passed: visibleInteractiveElements.length > 0,
            message: '页面缺少可操作控件'
          });
          var safeInteraction = visibleInteractiveElements.find(function (element) {
            var tag = element.tagName.toLowerCase();
            var type = (element.getAttribute('type') || '').toLowerCase();
            return tag === 'button' || (tag === 'input' && !['file', 'submit', 'reset'].includes(type));
          });
          if (safeInteraction) {
            try {
              safeInteraction.focus();
              safeInteraction.dispatchEvent(new Event('focus', { bubbles: true }));
              if (safeInteraction.tagName.toLowerCase() === 'input') {
                safeInteraction.dispatchEvent(new Event('input', { bubbles: true }));
              }
              cases.push({ name: 'primary-interaction-smoke', passed: true });
            } catch (error) {
              cases.push({
                name: 'primary-interaction-smoke',
                passed: false,
                message: error && error.message ? error.message : String(error),
                details: error && error.stack ? error.stack : null
              });
            }
          }
          if (typeof window.__MANTOU_SELF_TEST__ === 'function') {
            try {
              var appResult = await window.__MANTOU_SELF_TEST__();
              if (Array.isArray(appResult)) cases = cases.concat(appResult);
              else cases.push(appResult);
            } catch (error) {
              cases.push({
                name: 'app-defined-self-test',
                passed: false,
                message: error && error.message ? error.message : String(error),
                details: error && error.stack ? error.stack : null
              });
            }
          }
          return cases;
        })();
    """.trimIndent()

    val testSuite: String = """
        var duplicateIds = Array.from(document.querySelectorAll('[id]'))
          .map(function (element) { return element.id; })
          .filter(function (id, index, ids) { return id && ids.indexOf(id) !== index; });
        var remoteDependencies = Array.from(document.querySelectorAll('script[src], link[href], img[src]'))
          .map(function (element) { return element.src || element.href || ''; })
          .filter(function (url) {
            if (!url || /^(?:data|blob):/i.test(url)) return false;
            try {
              var parsed = new URL(url, location.href);
              return /^https?:$/i.test(parsed.protocol) && parsed.origin !== location.origin;
            } catch (_) {
              return true;
            }
          });
        var viewport = document.querySelector('meta[name="viewport"]');
        var root = document.documentElement;
        var horizontalOverflow = root.scrollWidth - root.clientWidth;
        var bridgeReady = !!(window.MantouApp && window.MantouApp.isMantouApp && window.MantouApp.isMantouApp());
        return [
          {
            name: 'unique-element-ids',
            passed: duplicateIds.length === 0,
            message: duplicateIds.length ? '发现重复 id: ' + Array.from(new Set(duplicateIds)).join(', ') : null
          },
          {
            name: 'offline-dependencies',
            passed: remoteDependencies.length === 0,
            message: remoteDependencies.length ? '发现远程依赖: ' + remoteDependencies.join(', ') : null
          },
          {
            name: 'mobile-viewport',
            passed: !!viewport && /width\s*=\s*device-width/i.test(viewport.content || ''),
            message: '缺少适用于移动端的 viewport'
          },
          {
            name: 'responsive-width',
            passed: horizontalOverflow <= 2,
            message: horizontalOverflow > 2 ? '页面横向溢出 ' + horizontalOverflow + 'px' : null
          },
          {
            name: 'mantou-tool-bridge',
            passed: bridgeReady,
            message: 'MantouApp WebView 工具桥未就绪'
          },
          {
            name: 'document-title',
            passed: document.title.trim().length > 0,
            message: '页面 title 为空'
          }
        ];
    """.trimIndent()

    fun diagnostics(report: WebInspectionReport): List<String> {
        val diagnosticMessages = report.diagnostics.map { diagnostic ->
            buildString {
                append('[').append(diagnostic.code).append("] ").append(diagnostic.message)
                diagnostic.location?.let { location ->
                    val source = location.source?.substringAfterLast('/')?.takeIf(String::isNotBlank)
                    if (source != null || location.line != null) {
                        append(" (")
                        append(source ?: "inline")
                        location.line?.let { append(':').append(it) }
                        location.column?.let { append(':').append(it) }
                        append(')')
                    }
                }
            }
        }
        val failedTests = report.selfTests.filterNot(WebSelfTestCase::passed).map { test ->
            "[${test.name}] ${test.message ?: "测试未通过"}"
        }
        return (diagnosticMessages + failedTests).distinct().take(MAX_DIAGNOSTICS)
    }

    private const val MAX_DIAGNOSTICS = 40
}
