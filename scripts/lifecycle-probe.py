import subprocess,time,shlex,pathlib,json,argparse
parser=argparse.ArgumentParser()
parser.add_argument('--serial',required=True)
parser.add_argument('--output',type=pathlib.Path,required=True)
args=parser.parse_args()
serial=args.serial;pkg='pro.perfectproduct.cramin.debug';root=args.output
root.mkdir(parents=True,exist_ok=True)
def shell(command,timeout=15):
 r=subprocess.run(['adb','-s',serial,'shell',command],capture_output=True,text=True,timeout=timeout)
 return r.stdout.strip()
def runas(command):return shell('run-as '+pkg+' sh -c '+shlex.quote(command))
def send(command,asynchronous=False):
 cmd=['adb','-s',serial,'shell','am','broadcast','--receiver-foreground','-n',pkg+'/pro.perfectproduct.cramin.probe.ProbeReceiver','--es','command',command]
 if asynchronous:return subprocess.Popen(cmd,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
 return subprocess.run(cmd,capture_output=True,text=True,timeout=15)
def wait(predicate,seconds=50):
 end=time.monotonic()+seconds
 while time.monotonic()<end:
  result=predicate()
  if result:return result
  time.sleep(.3)
 raise RuntimeError('checkpoint timeout')
def report():
 send('report');return runas('cat files/probe-report')
def ready():
 r=report();return r if 'status=READY' in r else None
if shell('getprop ro.kernel.qemu') != '1' or 'ProbeReceiver' not in shell('dumpsys package '+pkg):
 raise SystemExit('Requires a separate emulator with the opt-in probe APK installed')
print('seed synthetic document; notification permission is controlled by the caller',flush=True)
send('seed');print(wait(ready),flush=True);send('mark');baseline=wait(lambda: (r if 'progressMatches=true' in (r:=report()) else None));print(baseline,flush=True)
results=[]
for point in ['repo-snapshot','repo-prepared','replacementDeleted','restored','ready','committed']:
 runas('rm -f files/probe-reached files/probe-error; printf %s '+shlex.quote(point)+' > files/probe-stop')
 sender=send('reprocess',True)
 wait(lambda:runas('cat files/probe-reached')==point)
 before=shell('pidof '+pkg).split()[0]
 shell('run-as '+pkg+' kill -9 '+before)
 runas('rm -f files/probe-stop')
 shell('am start -n '+pkg+'/pro.perfectproduct.cramin.app.MainActivity')
 restored=wait(lambda:(r if 'status=READY' in (r:=report()) and 'progressMatches=true' in r else None),60)
 after=shell('pidof '+pkg).split()[0]
 assert before!=after
 item={'checkpoint':point,'termination':'SIGKILL','pidBefore':int(before),'pidAfter':int(after),'restored':restored}
 results.append(item);print(json.dumps(item),flush=True)
 (root/'process-death.json').write_text(json.dumps(results,indent=2))
 sender.poll()
# A separate force-stop case: record the stopped flag, then explicitly reopen.
point='replacementDeleted';runas('rm -f files/probe-reached; printf %s '+point+' > files/probe-stop');send('reprocess',True);wait(lambda:runas('cat files/probe-reached')==point)
shell('am force-stop '+pkg);stopped=shell('dumpsys package '+pkg)
assert 'stopped=true' in stopped
runas('rm -f files/probe-stop');time.sleep(1);assert not shell('pidof '+pkg)
shell('am start -n '+pkg+'/pro.perfectproduct.cramin.app.MainActivity')
r=wait(lambda:(r if 'status=READY' in (r:=report()) and 'progressMatches=true' in r else None),60)
results.append({'checkpoint':point,'termination':'force-stop','stoppedFlag':True,'recovery':'explicit Activity launch','restored':r})
(root/'process-death.json').write_text(json.dumps(results,indent=2));print('force-stop: explicit reopen restored progress; all scenarios passed',flush=True)
