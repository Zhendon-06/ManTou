package com.hfad.mantou.utils.harness

import com.google.gson.Gson
import com.hfad.mantou.utils.project.WebAppAcceptanceAction
import com.hfad.mantou.utils.project.WebAppAcceptanceActionType
import com.hfad.mantou.utils.project.WebAppAcceptanceAssertion
import com.hfad.mantou.utils.project.WebAppAcceptanceAssertionType
import com.hfad.mantou.utils.project.WebAppAcceptanceContract

object GeneratedAppHarnessScripts {

    private val gson = Gson()

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

    val testSuite: String = qualityGateSuite()

    fun qualityGateSuite(
        contract: WebQualityGateContract = WebQualityGateContract(),
        expectedViewport: WebQualityViewport? = null
    ): String {
        val contractJson = gson.toJson(contract)
        val viewportJson = gson.toJson(expectedViewport)
        return """
            return (function () {
              var contract = $contractJson;
              var expectedViewport = $viewportJson;
              var cases = [];
              var root = document.documentElement;
              var body = document.body;

              function result(name, passed, message, details) {
                cases.push({
                  name: name,
                  passed: passed === true,
                  message: passed ? null : message,
                  details: details == null ? null : details
                });
              }

              function isVisible(element) {
                if (!element || !element.isConnected) return false;
                var style = window.getComputedStyle(element);
                if (style.display === 'none' || style.visibility === 'hidden') return false;
                if (Number(style.opacity || 1) <= 0) return false;
                var rect = element.getBoundingClientRect();
                return rect.width > 0 && rect.height > 0;
              }

              function describe(element) {
                if (!element) return '<missing>';
                if (element.id) return '#' + element.id;
                var testId = element.getAttribute('data-testid');
                if (testId) return '[data-testid="' + testId + '"]';
                var name = element.getAttribute('name');
                if (name) return element.tagName.toLowerCase() + '[name="' + name + '"]';
                return element.tagName.toLowerCase();
              }

              function accessibleName(element) {
                var ariaLabel = (element.getAttribute('aria-label') || '').trim();
                if (ariaLabel) return ariaLabel;
                var labelledBy = (element.getAttribute('aria-labelledby') || '').trim();
                if (labelledBy) {
                  var labelledText = labelledBy.split(/\s+/).map(function (id) {
                    var label = document.getElementById(id);
                    return label ? (label.textContent || '').trim() : '';
                  }).filter(Boolean).join(' ');
                  if (labelledText) return labelledText;
                }
                if (element.labels && element.labels.length) {
                  var labels = Array.from(element.labels).map(function (label) {
                    return (label.textContent || '').trim();
                  }).filter(Boolean).join(' ');
                  if (labels) return labels;
                }
                var alt = (element.getAttribute('alt') || '').trim();
                if (alt) return alt;
                var text = (element.textContent || '').replace(/\s+/g, ' ').trim();
                if (text) return text;
                return (element.getAttribute('title') || '').trim();
              }

              function parseColor(value) {
                var match = String(value || '').match(/^rgba?\(([^)]+)\)$/i);
                if (!match) return null;
                var parts = match[1].replace(/\//g, ' ').split(/[\s,]+/).filter(Boolean);
                if (parts.length < 3) return null;
                function channel(part) {
                  if (/%$/.test(part)) return Math.round(parseFloat(part) * 2.55);
                  return Number(part);
                }
                var color = {
                  r: channel(parts[0]),
                  g: channel(parts[1]),
                  b: channel(parts[2]),
                  a: parts.length > 3 ? Number(parts[3]) : 1
                };
                if (![color.r, color.g, color.b, color.a].every(Number.isFinite)) return null;
                return color;
              }

              function composite(foreground, background) {
                var alpha = Math.max(0, Math.min(1, foreground.a));
                return {
                  r: foreground.r * alpha + background.r * (1 - alpha),
                  g: foreground.g * alpha + background.g * (1 - alpha),
                  b: foreground.b * alpha + background.b * (1 - alpha),
                  a: 1
                };
              }

              function resolvedBackground(element) {
                var layers = [];
                var current = element;
                while (current && current.nodeType === 1) {
                  var style = window.getComputedStyle(current);
                  if (style.backgroundImage && style.backgroundImage !== 'none') return null;
                  var color = parseColor(style.backgroundColor);
                  if (color && color.a > 0) layers.push(color);
                  current = current.parentElement;
                }
                var background = { r: 255, g: 255, b: 255, a: 1 };
                layers.reverse().forEach(function (layer) {
                  background = composite(layer, background);
                });
                return background;
              }

              function luminance(color) {
                function linear(channel) {
                  var normalized = channel / 255;
                  return normalized <= 0.04045
                    ? normalized / 12.92
                    : Math.pow((normalized + 0.055) / 1.055, 2.4);
                }
                return 0.2126 * linear(color.r) + 0.7152 * linear(color.g) + 0.0722 * linear(color.b);
              }

              function contrastRatio(foreground, background) {
                var first = luminance(foreground);
                var second = luminance(background);
                return (Math.max(first, second) + 0.05) / (Math.min(first, second) + 0.05);
              }

              var ids = Array.from(document.querySelectorAll('[id]')).map(function (element) {
                return element.id;
              }).filter(Boolean);
              var duplicateIds = ids.filter(function (id, index) { return ids.indexOf(id) !== index; });
              result(
                'unique-element-ids',
                duplicateIds.length === 0,
                '发现重复 id: ' + Array.from(new Set(duplicateIds)).join(', '),
                duplicateIds.slice(0, 12)
              );

              var remoteDependencies = Array.from(document.querySelectorAll('script[src], link[href], img[src], source[src], video[src], audio[src]'))
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
              result(
                'offline-dependencies',
                !contract.requireOfflineDependencies || remoteDependencies.length === 0,
                '发现远程依赖: ' + remoteDependencies.join(', '),
                remoteDependencies.slice(0, 12)
              );

              var viewport = document.querySelector('meta[name="viewport"]');
              var viewportContent = viewport ? (viewport.content || '') : '';
              result(
                'mobile-viewport',
                !!viewport && /width\s*=\s*device-width/i.test(viewportContent),
                '缺少适用于移动端的 viewport'
              );
              var zoomDisabled = /user-scalable\s*=\s*no/i.test(viewportContent) ||
                /maximum-scale\s*=\s*1(?:\.0+)?(?:\s|,|$)/i.test(viewportContent);
              result('viewport-zoom-enabled', !zoomDisabled, 'viewport 禁止了用户缩放');

              if (expectedViewport) {
                var widthDifference = Math.abs(window.innerWidth - expectedViewport.widthCssPixels);
                var heightDifference = Math.abs(window.innerHeight - expectedViewport.heightCssPixels);
                var viewportMatches = widthDifference <= expectedViewport.toleranceCssPixels &&
                  heightDifference <= expectedViewport.toleranceCssPixels;
                result(
                  'expected-viewport:' + expectedViewport.id,
                  viewportMatches,
                  '当前 viewport 为 ' + window.innerWidth + 'x' + window.innerHeight +
                    '，期望 ' + expectedViewport.widthCssPixels + 'x' + expectedViewport.heightCssPixels,
                  { widthDifference: widthDifference, heightDifference: heightDifference }
                );
              }

              var horizontalOverflow = root.scrollWidth - root.clientWidth;
              result(
                'responsive-width',
                horizontalOverflow <= contract.maxHorizontalOverflowCssPixels,
                '页面横向溢出 ' + horizontalOverflow + 'px',
                { scrollWidth: root.scrollWidth, clientWidth: root.clientWidth }
              );

              var bridgeReady = false;
              try {
                bridgeReady = !!(window.MantouApp && window.MantouApp.isMantouApp && window.MantouApp.isMantouApp());
              } catch (_) {
                bridgeReady = false;
              }
              result(
                'mantou-tool-bridge',
                !contract.requireMantouBridge || bridgeReady,
                'MantouApp WebView 工具桥未就绪'
              );
              result('document-title', document.title.trim().length > 0, '页面 title 为空');
              result(
                'document-language',
                (root.getAttribute('lang') || '').trim().length > 0,
                'html 元素缺少 lang 属性'
              );
              result(
                'primary-landmark',
                !!document.querySelector('main, [role="main"]'),
                '页面缺少 main 主内容区域'
              );

              var interactiveSelector = 'button, input:not([type="hidden"]), textarea, select, [role="button"], [role="link"], [role="checkbox"], [role="radio"], [role="switch"], a[href]';
              var interactiveElements = Array.from(document.querySelectorAll(interactiveSelector)).filter(isVisible);
              var unnamedInteractive = interactiveElements.filter(function (element) {
                return accessibleName(element).length === 0;
              });
              result(
                'interactive-accessible-names',
                unnamedInteractive.length === 0,
                '存在缺少可访问名称的交互控件',
                unnamedInteractive.slice(0, 12).map(describe)
              );

              var labelRequired = Array.from(document.querySelectorAll('input:not([type="hidden"]):not([type="button"]):not([type="submit"]):not([type="reset"]), textarea, select'))
                .filter(isVisible);
              var unlabelledControls = labelRequired.filter(function (element) {
                var ariaLabel = (element.getAttribute('aria-label') || '').trim();
                var labelledBy = (element.getAttribute('aria-labelledby') || '').trim();
                return !ariaLabel && !labelledBy && !(element.labels && element.labels.length);
              });
              result(
                'labelled-form-controls',
                unlabelledControls.length === 0,
                '存在没有 label 或 ARIA 标签的表单控件',
                unlabelledControls.slice(0, 12).map(describe)
              );

              var imagesWithoutAlternatives = Array.from(document.querySelectorAll('img')).filter(function (image) {
                var role = (image.getAttribute('role') || '').toLowerCase();
                return role !== 'presentation' && role !== 'none' && !image.hasAttribute('alt');
              });
              result(
                'image-alternatives',
                imagesWithoutAlternatives.length === 0,
                '存在缺少 alt 的图片',
                imagesWithoutAlternatives.slice(0, 12).map(describe)
              );

              var undersizedTargets = interactiveElements.filter(function (element) {
                if (element.hasAttribute('disabled') || element.getAttribute('aria-disabled') === 'true') return false;
                var style = window.getComputedStyle(element);
                var inlineTextLink = element.tagName.toLowerCase() === 'a' && style.display === 'inline' &&
                  !!element.closest('p, li, dd, dt, figcaption');
                if (contract.allowInlineTextTargetException && inlineTextLink) return false;
                var rect = element.getBoundingClientRect();
                return rect.width < contract.minTouchTargetCssPixels || rect.height < contract.minTouchTargetCssPixels;
              });
              result(
                'touch-target-size',
                undersizedTargets.length === 0,
                '交互控件触控区域小于 ' + contract.minTouchTargetCssPixels + 'px',
                undersizedTargets.slice(0, 12).map(function (element) {
                  var rect = element.getBoundingClientRect();
                  return describe(element) + ' (' + Math.round(rect.width) + 'x' + Math.round(rect.height) + ')';
                })
              );

              var hiddenFocusable = [];
              Array.from(document.querySelectorAll('[aria-hidden="true"]')).forEach(function (container) {
                if (container.matches && container.matches(interactiveSelector)) hiddenFocusable.push(container);
                hiddenFocusable = hiddenFocusable.concat(Array.from(container.querySelectorAll(interactiveSelector)));
              });
              hiddenFocusable = hiddenFocusable.filter(function (element) {
                  return !element.hasAttribute('disabled') && element.tabIndex >= 0;
                });
              result(
                'aria-hidden-focusables',
                hiddenFocusable.length === 0,
                'aria-hidden 区域包含可聚焦控件',
                hiddenFocusable.slice(0, 12).map(describe)
              );

              var contrastViolations = [];
              var contrastSkipped = 0;
              if (body) {
                Array.from(body.querySelectorAll('*')).filter(function (element) {
                  if (!isVisible(element)) return false;
                  if (/^(?:script|style|noscript)$/i.test(element.tagName)) return false;
                  return Array.from(element.childNodes).some(function (node) {
                    return node.nodeType === Node.TEXT_NODE && (node.nodeValue || '').trim().length > 0;
                  });
                }).forEach(function (element) {
                  var style = window.getComputedStyle(element);
                  var foreground = parseColor(style.color);
                  var background = resolvedBackground(element);
                  if (!foreground || !background || Number(style.opacity || 1) < 1) {
                    contrastSkipped += 1;
                    return;
                  }
                  foreground = composite(foreground, background);
                  var ratio = contrastRatio(foreground, background);
                  var fontSize = parseFloat(style.fontSize || '0');
                  var fontWeight = parseInt(style.fontWeight || '400', 10);
                  var isLarge = fontSize >= 24 || (fontSize >= 18.66 && fontWeight >= 700);
                  var minimum = isLarge
                    ? contract.minLargeTextContrastRatio
                    : contract.minNormalTextContrastRatio;
                  if (ratio + 0.01 < minimum) {
                    contrastViolations.push(describe(element) + ' (' + ratio.toFixed(2) + ':1)');
                  }
                });
              }
              result(
                'text-color-contrast',
                contrastViolations.length === 0,
                '文本颜色对比度未达到阈值',
                { violations: contrastViolations.slice(0, 12), skippedUnsupported: contrastSkipped }
              );

              return cases;
            })();
        """.trimIndent()
    }

    fun acceptanceSuite(contract: WebAppAcceptanceContract): String {
        validateAcceptanceContract(contract)
        val contractJson = gson.toJson(contract)
        return """
            return (async function () {
              var contract = $contractJson;
              var cases = [];
              var defaultTimeoutMillis = 2000;
              var settleMillis = 60;

              function pause(durationMillis) {
                return new Promise(function (resolve) {
                  window.setTimeout(resolve, Math.max(0, durationMillis));
                });
              }

              function describe(element, selector) {
                if (selector) return selector;
                if (!element) return '<missing>';
                if (element.id) return '#' + element.id;
                return element.tagName ? element.tagName.toLowerCase() : '<unknown>';
              }

              function queryAll(selector) {
                if (!selector) throw new Error('缺少 CSS selector');
                try {
                  return Array.from(document.querySelectorAll(selector));
                } catch (error) {
                  throw new Error('无效 CSS selector "' + selector + '": ' + (error.message || String(error)));
                }
              }

              function isVisible(element) {
                if (!element || !element.isConnected) return false;
                var style = window.getComputedStyle(element);
                if (style.display === 'none' || style.visibility === 'hidden') return false;
                if (Number(style.opacity || 1) <= 0) return false;
                var rect = element.getBoundingClientRect();
                return rect.width > 0 && rect.height > 0;
              }

              async function waitUntil(predicate, timeoutMillis, description) {
                var deadline = Date.now() + Math.max(0, timeoutMillis);
                var lastError = null;
                do {
                  try {
                    var value = predicate();
                    if (value) return value;
                  } catch (error) {
                    lastError = error;
                  }
                  await pause(40);
                } while (Date.now() <= deadline);
                if (lastError) throw lastError;
                throw new Error('等待超时: ' + description);
              }

              function nativeValueSetter(element) {
                var prototype = element instanceof HTMLTextAreaElement
                  ? HTMLTextAreaElement.prototype
                  : element instanceof HTMLSelectElement
                    ? HTMLSelectElement.prototype
                    : HTMLInputElement.prototype;
                var descriptor = Object.getOwnPropertyDescriptor(prototype, 'value');
                return descriptor && descriptor.set;
              }

              function setControlValue(element, value) {
                var setter = nativeValueSetter(element);
                if (setter) setter.call(element, value == null ? '' : String(value));
                else element.value = value == null ? '' : String(value);
                element.dispatchEvent(new Event('input', { bubbles: true }));
                element.dispatchEvent(new Event('change', { bubbles: true }));
              }

              function normalizeJsonValue(value) {
                if (typeof value !== 'string') return value;
                try { return JSON.parse(value); } catch (_) { return value; }
              }

              function deepEqual(first, second) {
                if (first === second) return true;
                if (typeof first !== typeof second || first == null || second == null) return false;
                if (Array.isArray(first) || Array.isArray(second)) {
                  if (!Array.isArray(first) || !Array.isArray(second) || first.length !== second.length) return false;
                  return first.every(function (item, index) { return deepEqual(item, second[index]); });
                }
                if (typeof first === 'object') {
                  var firstKeys = Object.keys(first).sort();
                  var secondKeys = Object.keys(second).sort();
                  if (!deepEqual(firstKeys, secondKeys)) return false;
                  return firstKeys.every(function (key) { return deepEqual(first[key], second[key]); });
                }
                return false;
              }

              async function executeAction(action, phase, trace) {
                var type = String(action.type || '').toUpperCase();
                var timeoutMillis = action.timeoutMs == null
                  ? defaultTimeoutMillis
                  : Math.max(0, Math.min(10000, Number(action.timeoutMs)));
                if (type === 'WAIT') {
                  var waitMillis = action.timeoutMs == null
                    ? (action.value == null ? settleMillis : Number(action.value))
                    : timeoutMillis;
                  if (!Number.isFinite(waitMillis) || Math.floor(waitMillis) !== waitMillis ||
                      waitMillis < 0 || waitMillis > 10000) {
                    throw new Error('WAIT 时长必须是 0 到 10000 的整数');
                  }
                  await pause(Math.max(0, Math.min(10000, waitMillis)));
                  trace.push({ phase: phase, type: type, passed: true, waitMillis: waitMillis });
                  return;
                }
                var selector = action.target;
                var element = await waitUntil(function () {
                  var match = queryAll(selector)[0];
                  return match && isVisible(match) ? match : null;
                }, timeoutMillis, selector || type);
                if (element.hasAttribute('disabled') || element.getAttribute('aria-disabled') === 'true') {
                  throw new Error(describe(element, selector) + ' 已禁用');
                }
                if (element.scrollIntoView) element.scrollIntoView({ block: 'center', inline: 'nearest' });
                if (type === 'CLICK') {
                  element.click();
                } else if (type === 'INPUT') {
                  if (!('value' in element)) throw new Error(describe(element, selector) + ' 不支持输入');
                  setControlValue(element, action.value);
                } else if (type === 'SELECT') {
                  if (!(element instanceof HTMLSelectElement)) {
                    throw new Error(describe(element, selector) + ' 不是 select 元素');
                  }
                  var optionExists = Array.from(element.options).some(function (option) {
                    return option.value === String(action.value == null ? '' : action.value);
                  });
                  if (!optionExists) throw new Error('select 中不存在值 ' + String(action.value));
                  setControlValue(element, action.value);
                } else if (type === 'SUBMIT') {
                  var form = element instanceof HTMLFormElement ? element : element.closest('form');
                  if (!form) throw new Error(describe(element, selector) + ' 不属于 form');
                  form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
                } else if (type === 'FOCUS') {
                  element.focus();
                  if (document.activeElement !== element) throw new Error(describe(element, selector) + ' 无法获得焦点');
                } else if (type === 'BLUR') {
                  element.blur();
                } else if (type === 'KEY_PRESS') {
                  var key = String(action.value || 'Enter');
                  element.focus();
                  element.dispatchEvent(new KeyboardEvent('keydown', { key: key, bubbles: true, cancelable: true }));
                  element.dispatchEvent(new KeyboardEvent('keypress', { key: key, bubbles: true, cancelable: true }));
                  element.dispatchEvent(new KeyboardEvent('keyup', { key: key, bubbles: true, cancelable: true }));
                } else {
                  throw new Error('不支持的验收动作: ' + type);
                }
                await pause(settleMillis);
                trace.push({ phase: phase, type: type, target: selector, passed: true });
              }

              function readStorage(key) {
                if (/^localStorage:/i.test(key)) {
                  var localKey = key.replace(/^localStorage:/i, '');
                  return { exists: localStorage.getItem(localKey) !== null, value: localStorage.getItem(localKey) };
                }
                if (window.MantouApp && window.MantouApp.storage &&
                    typeof window.MantouApp.storage.storageGet === 'function') {
                  var parsed = JSON.parse(window.MantouApp.storage.storageGet(key));
                  if (!parsed.success) throw new Error(parsed.error || 'storageGet 失败');
                  return {
                    exists: parsed.data && parsed.data.exists === true,
                    value: parsed.data ? parsed.data.valueJson : null
                  };
                }
                return { exists: localStorage.getItem(key) !== null, value: localStorage.getItem(key) };
              }

              function assertionOutcome(assertion) {
                var type = String(assertion.type || '').toUpperCase();
                var selector = assertion.target;
                var elements;
                if (type === 'URL_CONTAINS') {
                  return location.href.indexOf(String(assertion.value || '')) >= 0;
                }
                if (type === 'STORAGE_EQUALS') {
                  var stored = readStorage(String(selector || ''));
                  return stored.exists && deepEqual(
                    normalizeJsonValue(stored.value),
                    normalizeJsonValue(assertion.value)
                  );
                }
                elements = queryAll(selector);
                var element = elements[0];
                if (type === 'EXISTS') return elements.length > 0;
                if (type === 'NOT_EXISTS') return elements.length === 0;
                if (type === 'VISIBLE') return !!element && isVisible(element);
                if (type === 'HIDDEN') return !element || !isVisible(element);
                if (type === 'COUNT_EQUALS') return elements.length === Number(assertion.count);
                if (!element) return false;
                var normalizedText = (element.innerText || element.textContent || '').replace(/\s+/g, ' ').trim();
                if (type === 'TEXT_EQUALS') return normalizedText === String(assertion.value || '');
                if (type === 'TEXT_CONTAINS') return normalizedText.indexOf(String(assertion.value || '')) >= 0;
                if (type === 'VALUE_EQUALS') return String(element.value == null ? '' : element.value) === String(assertion.value || '');
                if (type === 'ATTRIBUTE_EQUALS') {
                  return element.getAttribute(String(assertion.attribute || '')) === String(assertion.value || '');
                }
                throw new Error('不支持的验收断言: ' + type);
              }

              function assertionDescription(assertion) {
                var value = assertion.count == null ? assertion.value : assertion.count;
                return String(assertion.type) + ' ' + String(assertion.target || '') +
                  (value == null ? '' : ' = ' + String(value));
              }

              async function executeAssertion(assertion, trace) {
                var description = assertionDescription(assertion);
                await waitUntil(function () {
                  return assertionOutcome(assertion);
                }, defaultTimeoutMillis, description);
                trace.push({ phase: 'assertion', type: assertion.type, target: assertion.target, passed: true });
              }

              for (var criterionIndex = 0; criterionIndex < contract.criteria.length; criterionIndex += 1) {
                var criterion = contract.criteria[criterionIndex];
                var trace = [];
                var passed = true;
                var message = null;
                try {
                  for (var setupIndex = 0; setupIndex < criterion.setup.length; setupIndex += 1) {
                    await executeAction(criterion.setup[setupIndex], 'setup', trace);
                  }
                  for (var actionIndex = 0; actionIndex < criterion.actions.length; actionIndex += 1) {
                    await executeAction(criterion.actions[actionIndex], 'action', trace);
                  }
                  await pause(settleMillis);
                  for (var assertionIndex = 0; assertionIndex < criterion.expected.length; assertionIndex += 1) {
                    await executeAssertion(criterion.expected[assertionIndex], trace);
                  }
                } catch (error) {
                  passed = false;
                  message = criterion.title + ': ' + (error && error.message ? error.message : String(error));
                  trace.push({ phase: 'failure', passed: false, error: message });
                }
                cases.push({
                  name: 'acceptance:' + criterion.priority + ':' + criterion.id,
                  passed: passed,
                  message: message,
                  details: {
                    criterionId: criterion.id,
                    priority: criterion.priority,
                    trace: trace
                  }
                });
              }
              cases.unshift({
                name: 'acceptance-contract-coverage',
                passed: cases.length === contract.criteria.length && cases.length > 0,
                message: cases.length > 0 ? null : 'AcceptanceContract 没有可执行 criterion',
                details: { expected: contract.criteria.length, executed: cases.length }
              });
              return cases;
            })();
        """.trimIndent()
    }

    fun deliverySuite(
        acceptanceContract: WebAppAcceptanceContract,
        qualityGateContract: WebQualityGateContract = WebQualityGateContract(),
        expectedViewport: WebQualityViewport? = null
    ): String {
        val qualitySource = gson.toJson(qualityGateSuite(qualityGateContract, expectedViewport))
        val acceptanceSource = gson.toJson(acceptanceSuite(acceptanceContract))
        return """
            return (async function () {
              function execute(source) {
                return Promise.resolve((new Function(source)).call(window));
              }
              var qualityCases = await execute($qualitySource);
              var acceptanceCases = await execute($acceptanceSource);
              return [].concat(qualityCases || [], acceptanceCases || []);
            })();
        """.trimIndent()
    }

    private fun validateAcceptanceContract(contract: WebAppAcceptanceContract) {
        require(contract.criteria.isNotEmpty()) { "acceptance contract must declare criteria" }
        require(contract.criteria.map { it.id }.distinct().size == contract.criteria.size) {
            "acceptance criterion ids must be unique"
        }
        contract.criteria.forEach { criterion ->
            require(criterion.id.isNotBlank()) { "acceptance criterion id must not be blank" }
            require(criterion.title.isNotBlank()) { "acceptance criterion title must not be blank" }
            require(criterion.expected.isNotEmpty()) {
                "acceptance criterion ${criterion.id} must declare assertions"
            }
            require((criterion.setup + criterion.actions).any {
                it.type != WebAppAcceptanceActionType.WAIT
            }) {
                "acceptance criterion ${criterion.id} must exercise at least one non-WAIT action"
            }
            (criterion.setup + criterion.actions).forEach(::validateAcceptanceAction)
            criterion.expected.forEach(::validateAcceptanceAssertion)
        }
    }

    private fun validateAcceptanceAction(action: WebAppAcceptanceAction) {
        require(action.timeoutMs == null || action.timeoutMs in 0..MAX_ACCEPTANCE_TIMEOUT_MILLIS) {
            "acceptance action timeout must be between 0 and $MAX_ACCEPTANCE_TIMEOUT_MILLIS"
        }
        when (action.type) {
            WebAppAcceptanceActionType.WAIT -> {
                val duration = action.timeoutMs ?: action.value?.let { value ->
                    requireNotNull(value.toIntOrNull()) {
                        "acceptance wait duration must be an integer"
                    }
                }
                require(duration == null || duration in 0..MAX_ACCEPTANCE_TIMEOUT_MILLIS) {
                    "acceptance wait duration must be between 0 and $MAX_ACCEPTANCE_TIMEOUT_MILLIS"
                }
            }
            else -> require(!action.target.isNullOrBlank()) {
                "acceptance action ${action.type} requires a target selector"
            }
        }
    }

    private fun validateAcceptanceAssertion(assertion: WebAppAcceptanceAssertion) {
        when (assertion.type) {
            WebAppAcceptanceAssertionType.URL_CONTAINS -> require(!assertion.value.isNullOrBlank()) {
                "URL_CONTAINS requires a non-blank value"
            }
            WebAppAcceptanceAssertionType.STORAGE_EQUALS -> {
                require(!assertion.target.isNullOrBlank()) { "STORAGE_EQUALS requires a storage key" }
                require(assertion.value != null) { "STORAGE_EQUALS requires a value" }
            }
            WebAppAcceptanceAssertionType.COUNT_EQUALS -> {
                require(!assertion.target.isNullOrBlank()) { "COUNT_EQUALS requires a target selector" }
                require(assertion.count != null && assertion.count >= 0) {
                    "COUNT_EQUALS requires a non-negative count"
                }
            }
            WebAppAcceptanceAssertionType.ATTRIBUTE_EQUALS -> {
                require(!assertion.target.isNullOrBlank()) {
                    "ATTRIBUTE_EQUALS requires a target selector"
                }
                require(!assertion.attribute.isNullOrBlank()) {
                    "ATTRIBUTE_EQUALS requires an attribute"
                }
                require(assertion.value != null) { "ATTRIBUTE_EQUALS requires a value" }
            }
            WebAppAcceptanceAssertionType.TEXT_EQUALS,
            WebAppAcceptanceAssertionType.TEXT_CONTAINS,
            WebAppAcceptanceAssertionType.VALUE_EQUALS -> {
                require(!assertion.target.isNullOrBlank()) {
                    "${assertion.type} requires a target selector"
                }
                if (assertion.type == WebAppAcceptanceAssertionType.TEXT_CONTAINS) {
                    require(!assertion.value.isNullOrBlank()) {
                        "TEXT_CONTAINS requires a non-blank value"
                    }
                } else {
                    require(assertion.value != null) { "${assertion.type} requires a value" }
                }
            }
            WebAppAcceptanceAssertionType.EXISTS,
            WebAppAcceptanceAssertionType.NOT_EXISTS,
            WebAppAcceptanceAssertionType.VISIBLE,
            WebAppAcceptanceAssertionType.HIDDEN -> require(!assertion.target.isNullOrBlank()) {
                "${assertion.type} requires a target selector"
            }
        }
    }

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
    private const val MAX_ACCEPTANCE_TIMEOUT_MILLIS = 10_000
}
