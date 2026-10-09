// Browser tests of the exact embedded page, with a simulated API only.
const fs=require('node:fs'),path=require('node:path'),assert=require('node:assert/strict');
const {chromium}=require('playwright');
const source=fs.readFileSync(path.join(__dirname,'../src/main.cpp'),'utf8');
const pageHtml=source.slice(source.indexOf('// The local AP serves commissioning')).match(/R"HTML\(([\s\S]*?)\)HTML"/)[1];
const out=path.resolve(__dirname,'../../tmp/dashboard-r390');fs.mkdirSync(out,{recursive:true});
(async()=>{
 const browser=await chromium.launch({channel:'chrome',headless:true});
 const page=await browser.newPage({viewport:{width:1280,height:900}});
 const errors=[];page.on('pageerror',e=>errors.push(e.message));page.on('dialog',d=>d.accept());
 let signed=false,fresh=true,needsChange=true,state='off',fail=false;
 const posts=[];
 await page.route('**/*',async route=>{
  const url=new URL(route.request().url()),p=url.pathname;
  if(p==='/')return route.fulfill({contentType:'text/html',body:pageHtml});
  if(fail)return route.abort();
  let code=200,j={};const req=route.request();
  if(req.method()==='POST'){posts.push({path:p,body:req.postData()});if(p.endsWith('/login')){signed=true;j={csrf_token:'test-token'}}else if(p.endsWith('/logout'))signed=false;else if(p.endsWith('/access')){needsChange=false;signed=false;j={result:'saved'}}else if(p.endsWith('/relay')){state=new URLSearchParams(req.postData()).get('state');code=202;j={result:'relay_command_queued'}}else j={result:'saved'};}
  else if(p.endsWith('/capabilities'))j={features:{mqtt:true}};
  else if(p.endsWith('/auth/session')){if(!signed){code=401;j={error:'authentication_required'}}else j={csrf_token:'test-token'}}
  else if(p.endsWith('/status'))j={device_id:'SP-84F3EB123456',firmware:{version:'R3.9.0-unified'},security:{password_change_required:needsChange},wifi:{access_point:{ssid:'SmartPlug-84F3EB123456'},station:{configured:true,status:'connected',ssid:'WiFi-Rumah',ip:'192.168.1.25'}},integration:{mode:'mqtt',connected:true},relay:{state,actuation_allowed:true},energy_persistence:{enabled:false,ready:false}};
  else if(p.endsWith('/measurements/latest'))j={has_sample:true,fresh,electrical:{voltage_v:229.5,current_a:.21,active_power_w:47.8,power_factor:.992,energy_wh:1234.567}};
  else if(p.endsWith('/settings/mqtt'))j={mode:'mqtt',host:'192.168.1.10',port:1883,username:'unit-user',topic:'smartplug/SP-84F3EB123456'};
  else {code=404;j={error:'not_found'}}
  await route.fulfill({status:code,contentType:'application/json',body:JSON.stringify(j)});
 });
 await page.goto('http://smartplug.test');await page.waitForFunction(()=>document.querySelector('#connection').textContent==='Terhubung ke perangkat');
 await page.screenshot({path:path.join(out,'login-desktop.png'),fullPage:false});
 assert.equal(await page.locator('#firmware').textContent(),'R3.9.0-unified');
 assert.equal(await page.locator('#ssid').isDisabled(),true);
 async function login(){await page.locator('#loginPassword').fill('test-owner-password');await page.locator('#loginForm button').click();await page.waitForFunction(()=>document.querySelector('#loginForm').classList.contains('hidden'));}
 await login();assert.equal(await page.locator('#apSsid').isDisabled(),false);assert.equal(await page.locator('#ssid').isDisabled(),true);
 await page.locator('#adminPassword').fill('new-test-password');await page.locator('#adminConfirm').fill('new-test-password');await page.locator('#accessForm button').click();await page.waitForFunction(()=>document.querySelector('#accessMessage').textContent.startsWith('Tersimpan.'));await page.evaluate(()=>poll());await login();
 assert.equal(await page.locator('#ssid').isDisabled(),false);
 await page.waitForFunction(()=>document.querySelector('#mqttHost').value==='192.168.1.10');
 await page.locator('#mqttHost').fill('broker-new.local');await page.locator('#ssid').fill('Nama-baru');await page.locator('#apSsid').fill('AP-baru');await page.locator('#integrationMode').selectOption('rest');await page.evaluate(()=>poll());
 assert.equal(await page.locator('#mqttHost').inputValue(),'broker-new.local');assert.equal(await page.locator('#ssid').inputValue(),'Nama-baru');assert.equal(await page.locator('#apSsid').inputValue(),'AP-baru');assert.equal(await page.locator('#integrationMode').inputValue(),'rest');
 assert.equal(await page.locator('#relayOffButton').isDisabled(),true);assert.equal(await page.locator('#relayOnButton').isDisabled(),false);
 await page.locator('#relayOnButton').click();await page.waitForFunction(()=>document.querySelector('#qcMessage').textContent.startsWith('Perintah diterima'));await page.evaluate(()=>poll());assert.equal(await page.locator('#relayOnButton').isDisabled(),true);assert.equal(await page.locator('#relayOffButton').isDisabled(),false);
 fresh=false;await page.evaluate(()=>poll());assert.equal(await page.locator('#voltage').textContent(),'—');
 fail=true;await page.evaluate(()=>poll());assert.equal(await page.locator('#relayOnButton').isDisabled(),true);assert.equal(await page.locator('#relayOffButton').isDisabled(),true);
 fail=false;fresh=true;await page.evaluate(()=>poll());await page.locator('#integrationMode').selectOption('mqtt');
 await page.screenshot({path:path.join(out,'desktop.png'),fullPage:true});
 for(const width of [390,320]){await page.setViewportSize({width,height:844});assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true);await page.screenshot({path:path.join(out,`mobile-${width}.png`),fullPage:true});await page.evaluate(()=>scrollTo(0,0));await page.screenshot({path:path.join(out,`mobile-top-${width}.png`),fullPage:false});}
 assert.deepEqual(errors,[]);
 fs.writeFileSync(path.join(out,'qa.json'),JSON.stringify({passed:true,checks:['password change gate','login','saved MQTT readback','dirty forms preserved','relay buttons','stale measurement','network failure','320/390 mobile no overflow','no JS errors'],mutations:posts.map(x=>x.path)},null,2));
 await browser.close();console.log('PASS: embedded dashboard simulated API regression tests; no hardware accessed.');
})().catch(e=>{console.error(e);process.exit(1)});
