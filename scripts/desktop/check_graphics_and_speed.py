import argparse,http.server,threading,time,io,json,pathlib,subprocess,os
from PIL import Image
arguments=argparse.ArgumentParser(description="Check PNG page assembly and bounded transfers in the real Suwayomi graphics runtime")
arguments.add_argument('--server-jar',type=pathlib.Path,required=True)
arguments.add_argument('--extension-jar',type=pathlib.Path,help='Use a packaged JAR instead of local build classes')
arguments.add_argument('--java',default='java')
arguments.add_argument('--javac',default='javac')
arguments.add_argument('--output',type=pathlib.Path,required=True)
args=arguments.parse_args()
ROOT=pathlib.Path(__file__).resolve().parents[2]
AUDIT=args.output.resolve();AUDIT.mkdir(parents=True,exist_ok=True)
subprocess.run([args.javac,'--release','17','-d',str(AUDIT),str(pathlib.Path(__file__).with_name('TransferDriver.java'))],check=True)
extension=[args.extension_jar.resolve()] if args.extension_jar else [ROOT/'src/ar/procomic/build/tmp/kotlin-classes/release',ROOT/'src/ar/procomic/build/intermediates/javac/release/compileReleaseJavaWithJavac/classes',ROOT/'src/ar/procomic/build/patched/procomic-classes.jar']
cp=os.pathsep.join(map(str,[AUDIT,*extension,args.server_jar.resolve()]))
colors=[(240,30,20),(20,210,30),(30,40,230),(240,200,20),(210,30,200),(20,210,220)]
png=[]
for color in colors:
 out=io.BytesIO();Image.new('RGB',(100,100),color).save(out,'PNG');png.append(out.getvalue())
order=[2,0,4,1,5,3];stats={'active':0,'peak':0};lock=threading.Lock()
class Handler(http.server.BaseHTTPRequestHandler):
 protocol_version='HTTP/1.1'
 def log_message(self,*args):pass
 def send(self,data,kind):
  self.send_response(200);self.send_header('Content-Type',kind);self.send_header('Content-Length',str(len(data)));self.send_header('Cache-Control','no-store');self.end_headers();self.wfile.write(data)
 def do_POST(self):
  assert self.path=='/chapter-map-proxy-plan/123',self.path
  self.rfile.read(int(self.headers['Content-Length']))
  plan={'dim':[300,200],'pieces':[f'http://127.0.0.1:{self.server.server_port}/piece/{i}.png' for i in range(6)],'order':order,'rects':[{'left':i%3*100,'top':i//3*100,'width':100,'height':100} for i in range(6)]}
  self.send(json.dumps({'data':{'map':plan}}).encode(),'application/json')
 def do_GET(self):
  with lock:stats['active']+=1;stats['peak']=max(stats['peak'],stats['active'])
  try:
   time.sleep(.2);self.send(png[int(self.path.split('/')[-1].split('.')[0])],'image/png')
  finally:
   with lock:stats['active']-=1
server=http.server.ThreadingHTTPServer(('127.0.0.1',0),Handler);threading.Thread(target=server.serve_forever,daemon=True).start();base=f'http://127.0.0.1:{server.server_port}'
def run(*driver_args):
 r=subprocess.run([args.java,'-Djava.awt.headless=true','-cp',cp,'TransferDriver',*driver_args],text=True,capture_output=True,timeout=60)
 if r.returncode:print(r.stdout,r.stderr);raise RuntimeError('Desktop runtime check failed')
 return r.stdout.strip().splitlines()[-1]
try:
 print(run('stitch',base,str(AUDIT/'assembled-fixture.jpg')),flush=True)
 image=Image.open(AUDIT/'assembled-fixture.jpg');assert image.size==(300,200)
 for i,source in enumerate(order):
  actual=image.getpixel((i%3*100+50,i//3*100+50));assert max(abs(a-b) for a,b in zip(actual,colors[source]))<12,(actual,colors[source])
 print('Suwayomi graphics: dimensions, all six destinations and colors passed',flush=True)
 urls=AUDIT/'fixture-urls.txt';urls.write_text('\n'.join(base+f'/piece/{i}.png' for i in order))
 result=[]
 for mode in ['serial','parallel']:
  with lock:stats['peak']=0
  row=json.loads(run(mode,str(urls)));row['peak_transfers']=stats['peak'];result.append(row)
  print(mode,row['milliseconds'],'ms','peak',row['peak_transfers'],flush=True)
 assert result[0]['hashes']==result[1]['hashes'];assert 2<=result[1]['peak_transfers']<=3
 public={'fixture':'six PNG pieces, 200 ms server delay per transfer','graphics_runtime':'Provided Suwayomi 2.4.2366 AppImage','pixel_checks':'passed','serial_ms':result[0]['milliseconds'],'parallel_ms':result[1]['milliseconds'],'parallel_transfer_limit':3,'bytes_identical':True}
 (AUDIT/'fixture-benchmark.json').write_text(json.dumps(public,indent=2));print('Observed fixture speedup:',round(result[0]['milliseconds']/result[1]['milliseconds'],2),flush=True)
finally:server.shutdown();server.server_close()
