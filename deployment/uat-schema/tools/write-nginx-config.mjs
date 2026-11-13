import fs from 'node:fs';
import path from 'node:path';
function main() {
  const args=process.argv.slice(2);
  if(args.length!==6||args[0]!=='--root'||args[2]!=='--run-id'||args[4]!=='--manager-root'||!/^UAT_[0-9]{17}$/.test(args[3]))
    throw new Error('Usage: node write-nginx-config.mjs --root <root> --run-id <run> --manager-root <built-static-directory>');
  const root=fs.realpathSync(args[1]),run=fs.realpathSync(path.join(root,'.uat-runtime',args[3])),web=fs.realpathSync(args[5]);
  const owner=JSON.parse(fs.readFileSync(path.join(run,'owner.json'))),settings=JSON.parse(fs.readFileSync(path.join(run,'runtime/api-settings.json')));
  if(owner.purpose!=='WECHAT_TEST'||owner.integration!==true||owner.runId!==args[3]||settings.runId!==args[3]||fs.realpathSync(owner.workspace)!==root)
    throw new Error('Owned test configuration required');
  if(!fs.existsSync(path.join(web,'index.html'))||!fs.existsSync(path.join(web,'config.js')))throw new Error('Built Manager with runtime config required');
  if(/[;"\\\r\n]/.test(run+web))throw new Error('Unsupported Nginx path characters');
  const origin=new URL(settings.publicOrigin),port=Number(origin.port||443),host=origin.hostname;
  if(origin.protocol!=='https:'||!/^[a-z0-9.:[\]-]+$/i.test(host)||!Number.isInteger(port)||port<1||port>65535)
    throw new Error('Invalid HTTPS entry');
  const lines=[
    '# Generated for '+args[3]+' / '+settings.version,
    'server {','  listen '+port+' ssl;','  server_name '+host+';',
    '  ssl_protocols TLSv1.2 TLSv1.3;',
    '  ssl_certificate "'+run+'/private/tls/cert.pem";',
    '  ssl_certificate_key "'+run+'/private/tls/key.pem";',
    '  client_max_body_size 20m;',
    '  add_header X-Content-Type-Options nosniff always;',
    '  add_header Referrer-Policy no-referrer always;',
    '  add_header X-Frame-Options DENY always;',
    '  location /api/buyer/ { proxy_pass http://127.0.0.1:'+settings.buyer.port+'/buyer/; proxy_read_timeout 35s; }',
    '  location /api/manager/ { proxy_pass http://127.0.0.1:'+settings.manager.port+'/manager/; proxy_read_timeout 35s; }',
    '  location /api/store/ { proxy_pass http://127.0.0.1:'+settings.manager.port+'/store/; proxy_read_timeout 35s; }',
    '  location /api/common/ { proxy_pass http://127.0.0.1:'+settings.manager.port+'/common/; proxy_read_timeout 35s; }',
    '  location /api/ { return 404; }',
    '  location /files/ { alias "'+run+'/files/public/"; autoindex off; default_type application/octet-stream; types { image/png png; image/jpeg jpg jpeg; image/webp webp; } add_header Content-Disposition attachment; add_header Content-Security-Policy "default-src \'none\'; sandbox"; limit_except GET HEAD { deny all; } }',
    '  location = /config.js { root "'+web+'"; add_header Cache-Control no-store; }',
    '  location / { root "'+web+'"; try_files $uri $uri/ /index.html; }',
    '  location ~ /\\. { deny all; }','}'
  ];
  fs.writeFileSync(path.join(run,'runtime/nginx-site.conf'),lines.join('\n')+'\n',{mode:0o600});
  console.log('NGINX_CONFIGURED '+origin.origin+'; install the certificate and run nginx -t before reload');
}
try{main();}catch(e){console.error('NGINX_CONFIGURATION_FAILED: '+e.message);process.exitCode=1;}
