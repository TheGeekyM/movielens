<?php
// MovieLens proxy: the app talks to this file instead of api.anthropic.com, so the real API key never ships in the APK.
// Env: ANTHROPIC_API_KEY (real key), APP_TOKEN (what you type into the app as its key).
// App "Server URL": https://your-host/proxy.php   (the SDK appends /v1/messages, read here via PATH_INFO)

header('Content-Type: application/json');
$token = getenv('APP_TOKEN');
if (!$token || !hash_equals($token, $_SERVER['HTTP_X_API_KEY'] ?? '')) { http_response_code(401); exit('{"error":"unauthorized"}'); }
if (($_SERVER['PATH_INFO'] ?? '') !== '/v1/messages' || $_SERVER['REQUEST_METHOD'] !== 'POST') { http_response_code(404); exit('{"error":"not_found"}'); }

// ponytail: per-IP fixed window in the temp dir, 60 requests/minute. Use Redis if this runs on more than one server.
$f = sys_get_temp_dir() . '/movielens_' . md5($_SERVER['REMOTE_ADDR']) . '_' . intdiv(time(), 60);
$n = (int) @file_get_contents($f) + 1;
file_put_contents($f, (string) $n);
if ($n > 60) { http_response_code(429); exit('{"error":"rate_limited"}'); }

// Only the cheap model the app uses, so a leaked token can't run up an Opus bill.
$body = file_get_contents('php://input');
if ((json_decode($body, true)['model'] ?? '') !== 'claude-haiku-5-5') { http_response_code(400); exit('{"error":"model_not_allowed"}'); }

$headers = ['content-type: application/json', 'x-api-key: ' . getenv('ANTHROPIC_API_KEY'),
    'anthropic-version: ' . ($_SERVER['HTTP_ANTHROPIC_VERSION'] ?? '2023-06-01')];
if (!empty($_SERVER['HTTP_ANTHROPIC_BETA'])) $headers[] = 'anthropic-beta: ' . $_SERVER['HTTP_ANTHROPIC_BETA'];

$ch = curl_init('https://api.anthropic.com/v1/messages');
curl_setopt_array($ch, [CURLOPT_POST => true, CURLOPT_POSTFIELDS => $body, CURLOPT_HTTPHEADER => $headers,
    CURLOPT_RETURNTRANSFER => true, CURLOPT_TIMEOUT => 120]);
$out = curl_exec($ch);
http_response_code($out === false ? 502 : curl_getinfo($ch, CURLINFO_HTTP_CODE));
echo $out === false ? '{"error":"upstream_unreachable"}' : $out;
