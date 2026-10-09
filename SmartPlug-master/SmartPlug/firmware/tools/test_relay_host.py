"""Run the real LatchingRelay class on a simulated clock/GPIO. No hardware I/O."""
from pathlib import Path
import subprocess
import os
root=Path(__file__).resolve().parents[1]
source=(root/'src/main.cpp').read_text(encoding='utf-8')
declaration=source.split('class LatchingRelay {',1)[1].split('// ===== END LatchingRelay.h =====',1)[0]
implementation=source.split('// ===== BEGIN LatchingRelay.cpp =====',1)[1].split('// ===== END LatchingRelay.cpp =====',1)[0]
prelude=r'''
#include <cassert>
#include <cstdint>
#include <cstring>
#include <cstdio>
constexpr int LOW=0,HIGH=1,OUTPUT=1;
uint32_t clockMs=0;
int pins[2]={0,0};
unsigned long millis(){return clockMs;}
void digitalWrite(uint8_t pin,int value){pins[pin]=value;assert(!(pins[0]&&pins[1]));}
void pinMode(uint8_t,int){}
'''
test=r'''
int main(){
 LatchingRelay relay(0,1,50,1000,true);relay.begin();
 assert(!pins[0]&&!pins[1]);assert(!strcmp(relay.stateText(),"unknown"));
 assert(relay.requestOn());assert(pins[1]==HIGH&&pins[0]==LOW);assert(relay.busy());
 assert(!strcmp(relay.stateText(),"transitioning"));assert(!relay.requestOff());
 clockMs=49;relay.tick();assert(relay.busy());
 clockMs=50;relay.tick();assert(!relay.busy());assert(!pins[0]&&!pins[1]);assert(!strcmp(relay.stateText(),"on"));
 clockMs=51;assert(relay.requestOn());assert(!relay.busy());assert(!pins[0]&&!pins[1]);assert(!relay.requestOff());
 clockMs=1050;assert(relay.requestOff());assert(pins[0]==HIGH&&pins[1]==LOW);
 clockMs=1100;relay.tick();assert(!strcmp(relay.stateText(),"off"));assert(!pins[0]&&!pins[1]);
 LatchingRelay disabled(0,1,50,1000,false);disabled.begin();assert(!disabled.requestOn());assert(!disabled.requestOff());
 LatchingRelay wrap(0,1,50,1000,true);wrap.begin();clockMs=0xfffffff0U;assert(wrap.requestOn());
 clockMs=0x21U;wrap.tick();assert(wrap.busy());clockMs=0x22U;wrap.tick();assert(!wrap.busy());
 puts("PASS: real relay class - coil exclusivity, pulse completion, cooldown, idempotency, disabled profile, clock wrap.");
}
'''
folder=root/'.pio/host-relay-test';folder.mkdir(exist_ok=True)
cpp=folder/'relay_test.cpp';exe=folder/'relay_test.exe'
cpp.write_text(prelude+'\nclass LatchingRelay {'+declaration+implementation+test,encoding='utf-8')
compiler=Path('C:/Users/MSI/.platformio/packages/toolchain-gccmingw32/bin/g++.exe')
environment=dict(os.environ)
environment['PATH']=str(compiler.parent)+os.pathsep+environment.get('PATH','')
subprocess.run([str(compiler),'-std=c++17','-Wall','-Wextra',str(cpp),'-o',str(exe)],check=True,env=environment)
subprocess.run([str(exe)],check=True,env=environment)
