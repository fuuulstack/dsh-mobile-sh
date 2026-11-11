export class Budget {
  used=0;
  constructor(readonly limit:number,readonly name='budget'){}
  take(bytes:number):()=>void {
    if(!Number.isSafeInteger(bytes)||bytes<0||this.used+bytes>this.limit)throw new Error(`${this.name}-capacity`);
    this.used+=bytes;let held=true;
    return ()=>{if(held){held=false;this.used-=bytes;}};
  }
}
export class Admission {
  readonly small:Budget;readonly large=new Budget(1,'large');readonly streams:Budget;readonly ws:Budget;
  constructor(readonly node:Budget,readonly threshold:number,small:number,streams:number,ws:number){
    this.small=new Budget(small,'small');this.streams=new Budget(streams,'streams');this.ws=new Budget(ws,'ws');
  }
  acquire(bytes:number,wsBytes=0):()=>void {
    const leases:Array<()=>void>=[];
    try{leases.push(this.streams.take(1));if(bytes)leases.push((bytes<=this.threshold?this.small:this.large).take(bytes<=this.threshold?bytes:1),this.node.take(bytes));
      if(wsBytes)leases.push(this.ws.take(wsBytes),this.node.take(wsBytes));
    }catch(e){leases.reverse().forEach(f=>f());throw e;}
    let held=true;return ()=>{if(held){held=false;leases.reverse().forEach(f=>f());}};
  }
}
