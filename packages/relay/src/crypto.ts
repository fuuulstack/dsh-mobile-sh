import { argon2, randomBytes, createHash, timingSafeEqual, createHmac, createCipheriv, createDecipheriv } from 'node:crypto';
export const secret=(bytes=32)=>randomBytes(bytes).toString('base64url');
export const uuid=()=>randomBytes(16).toString('hex');
export const hash=(value:string)=>createHash('sha256').update(value).digest('hex');
export function equal(a:string,b:string){const x=Buffer.from(a),y=Buffer.from(b);return x.length===y.length&&timingSafeEqual(x,y);}
async function derive(password:string,salt:Buffer):Promise<Buffer>{return new Promise((resolve,reject)=>argon2('argon2id',{
  message:password,nonce:salt,parallelism:1,tagLength:32,memory:65536,passes:3,
},(e,b)=>e?reject(e):resolve(b)));}
export async function passwordHash(value:string){if(value.length<12||value.length>256)throw new Error('password-length');const salt=randomBytes(16);return `argon2id$${salt.toString('base64url')}$${(await derive(value,salt)).toString('base64url')}`;}
export async function passwordValid(value:string,record:string){const [alg,salt,digest]=record.split('$');if(alg!=='argon2id'||!salt||!digest||value.length>256)return false;return equal((await derive(value,Buffer.from(salt,'base64url'))).toString('base64url'),digest);}
const alphabet='ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
export function totpSecret(){let bits=0,value=0,out='';for(const b of randomBytes(20)){value=(value<<8)|b;bits+=8;while(bits>=5){bits-=5;out+=alphabet[(value>>>bits)&31];}}return out;}
function unbase32(s:string){let bits=0,v=0;const out:number[]=[];for(const c of s){const n=alphabet.indexOf(c);if(n<0)throw new Error('totp-secret');v=(v<<5)|n;bits+=5;if(bits>=8){bits-=8;out.push((v>>>bits)&255);}}return Buffer.from(out);}
export function totp(s:string,step=Math.floor(Date.now()/30000)){const b=Buffer.alloc(8);b.writeBigUInt64BE(BigInt(step));const h=createHmac('sha1',unbase32(s)).update(b).digest(),o=h[19]!&15;return ((h.readUInt32BE(o)&0x7fffffff)%1000000).toString().padStart(6,'0');}
export function totpStep(s:string,code:string,now:number){if(!/^\d{6}$/.test(code))return -1;const current=Math.floor(now/30000);for(const d of [-1,0,1])if(equal(totp(s,current+d),code))return current+d;return -1;}
export function encrypt(key:Buffer,value:string){const iv=randomBytes(12),c=createCipheriv('aes-256-gcm',key,iv),body=Buffer.concat([c.update(value,'utf8'),c.final()]);return Buffer.concat([iv,c.getAuthTag(),body]).toString('base64url');}
export function decrypt(key:Buffer,value:string){const b=Buffer.from(value,'base64url'),d=createDecipheriv('aes-256-gcm',key,b.subarray(0,12));d.setAuthTag(b.subarray(12,28));return Buffer.concat([d.update(b.subarray(28)),d.final()]).toString('utf8');}
