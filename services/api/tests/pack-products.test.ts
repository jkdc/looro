import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { PackPurchases, makePackProduct, BYOK_UNLOCK_PRICE_MINOR, TOKEN_PACK_PRICE_MINOR, TOKEN_PACK_CREDIT_NANO,
  type PackEntitlementKind, type PackProductInput } from '../src/pack-products.js';
import { MinutePurchases, type MinutePurchaseVerifier, type VerifiedMinutePurchase, type PurchaseEnvironment } from '../src/minute-purchases.js';
import { AIValuePurchases, makeAIValueProduct, PurchaseFulfillmentRouter, refundedAIValue } from '../src/ai-value-purchases.js';
import { connectDatabase } from '../src/db.js';
import { migrate } from '../src/migrate.js';
import { paidAIBalance, reserve, settle } from '../src/ledger.js';
import { MinuteReceiptVault, MinuteDeliveryWorker } from '../src/minute-provider-delivery.js';

const databaseURL=process.env.TEST_DATABASE_URL;
if (databaseURL && !new URL(databaseURL).pathname.endsWith('_test')) throw new Error('Use an isolated test database.');
const integration=(name:string,fn:()=>Promise<void>)=>test(name,{skip:!databaseURL&&'Set TEST_DATABASE_URL.'},fn);
// Synthetic amounts only. No merchant offers or live provider calls are created by these tests.
function input(kind:PackEntitlementKind,environment:PurchaseEnvironment='live',overrides:Partial<PackProductInput>={}):PackProductInput {
  return {provider:'stripe',environment,merchant:'acct_syntheticpack',
    sku:kind==='byok_unlock'?'synthetic-unlock':'synthetic-pack',
    providerProduct:kind==='byok_unlock'?'price_synthetic_unlock':'price_synthetic_pack',
    currency:'usd',totalMinor:kind==='byok_unlock'?BYOK_UNLOCK_PRICE_MINOR:TOKEN_PACK_PRICE_MINOR,
    entitlementKind:kind,creditNanoUSD:kind==='token_pack'?TOKEN_PACK_CREDIT_NANO.toString():'0',...overrides};
}
async function fixture() {
  const schema=`pack_${randomUUID().replaceAll('-','')}`,url=new URL(databaseURL!);url.searchParams.set('options',`-c search_path=${schema}`);
  const db=connectDatabase(url.toString());await db.query(`CREATE SCHEMA ${schema}`);await migrate(db);
  const evidence=new Map<string,VerifiedMinutePurchase>();let verifications=0;
  const unlockProduct=makePackProduct(input('byok_unlock')),packProduct=makePackProduct(input('token_pack'));
  const verifier:MinutePurchaseVerifier={provider:'stripe',environment:'live',merchant:'acct_syntheticpack',verify:async value=>{
    verifications++;if (typeof value!=='string'||!evidence.has(value)) throw new Error('Provider verification failed');return evidence.get(value)!;
  }};
  const packs=new PackPurchases(db,{catalog:[unlockProduct,packProduct],verifiers:[verifier],salesEnabled:true});
  const legacy=new MinutePurchases(db,{catalog:[{provider:'stripe',environment:'live',merchant:'acct_syntheticpack',sku:'historical-synthetic',
    providerProduct:'price_historical',currency:'usd',totalMinor:997,minutes:30}],verifiers:[verifier],salesEnabled:true});
  const ai=new AIValuePurchases(db,{catalog:[makeAIValueProduct({provider:'stripe',environment:'live',merchant:'acct_syntheticpack',sku:'synthetic-ai',
    providerProduct:'price_synthetic_ai',currency:'usd',currencyExponent:2,aiValueMinor:1000,policyVersion:1,serviceFeeBasisPoints:1500,
    processing:{rateBasisPoints:0,fixedMinor:0,bufferBasisPoints:0},exchangeRate:{numerator:'1',denominator:'1',version:'synthetic-usd'},
    estimate:{nanoUSDPerMinute:'100000000',rateVersion:'synthetic-estimate'}})],verifiers:[verifier],salesEnabled:true});
  const router=new PurchaseFulfillmentRouter(db,legacy,ai,[verifier],packs);
  return {db,packs,legacy,ai,router,unlockProduct,packProduct,verifier,evidence,schema,url:url.toString(),get verifications(){return verifications;},
    async account(guest=false){const id=randomUUID();await db.query('INSERT INTO accounts(id,is_guest) VALUES($1,$2)',[id,guest]);await db.query('INSERT INTO wallets(account_id) VALUES($1)',[id]);return id;},
    order(kind:PackEntitlementKind,account:string,key=randomUUID()){return packs.createOrder(account,'stripe',kind==='byok_unlock'?unlockProduct.sku:packProduct.sku,key);},
    proof(order:{orderID:string;providerProduct:string;currency:string;totalMinor:number},overrides:Partial<VerifiedMinutePurchase>={}){
      const value:VerifiedMinutePurchase={provider:'stripe',environment:'live',merchant:'acct_syntheticpack',orderID:order.orderID,
        transactionID:`synthetic:${randomUUID()}`,eventID:`snapshot:${randomUUID()}`,providerProduct:order.providerProduct,quantity:1,
        currency:order.currency,totalMinor:order.totalMinor,state:'purchased',refundedMinor:0,...overrides};
      const key=randomUUID();evidence.set(key,value);return {key,value};
    },async entitlement(account:string){return (await db.query('SELECT * FROM entitlements WHERE account_id=$1',[account])).rows[0];},
    async unlockedAt(account:string){return (await db.query('SELECT unlocked_at FROM entitlements WHERE account_id=$1',[account])).rows[0]?.unlocked_at ?? null;},
    async cleanup(){await db.query(`DROP SCHEMA ${schema} CASCADE`);await db.end();}};
}

test('pack products are code constants: approved price, credit and kind resist catalog tampering',()=>{
  const unlock=makePackProduct(input('byok_unlock')),pack=makePackProduct(input('token_pack'));
  assert.equal(unlock.totalMinor,699);assert.equal(unlock.creditNanoUSD,'0');assert.equal(unlock.entitlementKind,'byok_unlock');
  assert.equal(pack.totalMinor,499);assert.equal(pack.creditNanoUSD,'2160000000');assert.equal(pack.entitlementKind,'token_pack');
  const verifier={...unlock,verify:async()=>{throw new Error();}};
  for (const bad of [input('byok_unlock','live',{totalMinor:698}),input('token_pack','live',{totalMinor:500}),
    input('token_pack','live',{creditNanoUSD:'0'}),input('byok_unlock','live',{creditNanoUSD:'2160000000'}),
    input('token_pack','live',{currency:'eur'}),{...input('token_pack'),entitlementKind:'ai_value'},
    {...input('token_pack'),sku:'bad sku'}] as PackProductInput[])
    assert.throws(()=>new PackPurchases({} as any,{catalog:[bad],verifiers:[verifier]}),/invalid_pack_product/);
  for (const change of [input('token_pack','live',{providerProduct:''}),{...input('byok_unlock'),merchant:''}] as PackProductInput[])
    assert.throws(()=>makePackProduct(change),/invalid_pack_product/);
  assert.equal(refundedAIValue(TOKEN_PACK_CREDIT_NANO,17,TOKEN_PACK_PRICE_MINOR),(TOKEN_PACK_CREDIT_NANO*17n+499n)/499n);
});
integration('default-off pack sales still dispatch and guests can never order packs',async()=>{
  const f=await fixture();try{
    const member=await f.account(),disabled=new PackPurchases(f.db,{catalog:[f.packProduct],verifiers:[f.verifier]});
    assert.deepEqual(disabled.products('stripe'),[]);assert.equal(disabled.hasProduct('stripe',f.packProduct.sku),true);
    await assert.rejects(disabled.createOrder(member,'stripe',f.packProduct.sku,randomUUID()),/pack_purchases_unavailable/);
    await assert.rejects(f.order('token_pack',await f.account(true)),/purchase_requires_account/);
    assert.equal((await f.db.query('SELECT count(*) FROM minute_purchase_orders')).rows[0].count,'0');
  }finally{await f.cleanup();}
});
integration('pack orders pin an immutable quote with credit and rate version; unlock pins no rate',async()=>{
  const f=await fixture();try{
    const member=await f.account(),key=randomUUID(),pack=await f.order('token_pack',member,key),unlock=await f.order('byok_unlock',member);
    assert.equal(pack.creditNanoUSD,'2160000000');assert.equal(unlock.creditNanoUSD,'0');assert.equal(unlock.totalMinor,699);
    const quotes=(await f.db.query('SELECT order_id,kind,credit_nano,rate_version FROM pack_purchase_quotes ORDER BY kind')).rows;
    assert.deepEqual(quotes,[{order_id:unlock.orderID,kind:'byok_unlock',credit_nano:'0',rate_version:null},
      {order_id:pack.orderID,kind:'token_pack',credit_nano:'2160000000',rate_version:'2026-09-minimax-m'}]);
    for (const id of [pack.orderID,unlock.orderID])
      assert.equal((await f.db.query('SELECT allowance_ms FROM minute_purchase_orders WHERE id=$1',[id])).rows[0].allowance_ms,null);
    await assert.rejects(f.db.query('UPDATE pack_purchase_quotes SET credit_nano=1 WHERE order_id=$1',[pack.orderID]),/immutable/);
    await assert.rejects(f.db.query(`UPDATE minute_purchase_orders SET entitlement_kind='minutes',allowance_ms=60000 WHERE id=$1`,[pack.orderID]),/immutable/);
    assert.deepEqual(await f.order('token_pack',member,key),pack);
    await assert.rejects(f.packs.createOrder(member,'stripe',f.unlockProduct.sku,key),/idempotency_conflict/);
    await assert.rejects(f.db.query(`INSERT INTO minute_purchase_orders(id,account_id,idempotency_key,provider,environment,merchant,sku,provider_product,currency,total_minor,allowance_ms,entitlement_kind)
      VALUES($1,$2,$3,'stripe','live','acct_syntheticpack','missing','price_missing','usd',499,NULL,'token_pack')`,[randomUUID(),member,randomUUID()]),/pack_quote_required/);
    await assert.rejects(f.packs.createOrder(member,'stripe','unknown-pack',randomUUID()),/pack_product_unavailable/);
  }finally{await f.cleanup();}
});
integration('verified pack purchase and concurrent replays credit the exact nano once with the pinned rate version',async()=>{
  const f=await fixture();try{
    const member=await f.account(),order=await f.order('token_pack',member),proof=f.proof(order);
    const values=await Promise.all(Array.from({length:5},()=>f.router.reconcile('stripe',proof.key)));
    assert.equal(f.verifications,5);assert.ok(values.every(value=>value.fulfillmentRecorded));
    const balance=await paidAIBalance(f.db,member);
    assert.equal(balance.balanceNanoUSD,'2160000000');assert.equal(balance.availableNanoUSD,'2160000000');
    assert.equal((await f.db.query('SELECT sandbox_balance_nano FROM wallets WHERE account_id=$1',[member])).rows[0].sandbox_balance_nano,'0');
    const ledger=(await f.db.query("SELECT kind,balance_delta_nano,sandbox_delta_nano,rate_version FROM ledger WHERE account_id=$1",[member])).rows;
    assert.deepEqual(ledger,[{kind:'purchase',balance_delta_nano:'2160000000',sandbox_delta_nano:'0',rate_version:'2026-09-minimax-m'}]);
    assert.equal((await f.db.query('SELECT count(*) FROM minute_entries WHERE account_id=$1',[member])).rows[0].count,'0');
    await assert.rejects(f.legacy.reconcile('stripe',proof.key),/purchase_entitlement_mismatch/);
    await assert.rejects(f.packs.status(await f.account(),order.orderID),/purchase_not_found/);
  }finally{await f.cleanup();}
});
integration('unlock delivery is set-once: replays and later orders never move the unlock',async()=>{
  const f=await fixture();try{
    const member=await f.account(),first=await f.order('byok_unlock',member),proof=f.proof(first);
    await f.router.reconcile('stripe',proof.key);
    const granted=await f.entitlement(member);
    assert.equal(granted.unlock_source,'stripe');assert.equal(granted.unlock_order_id,first.orderID);assert.ok(granted.unlocked_at);
    await Promise.all(Array.from({length:4},()=>f.router.reconcile('stripe',f.proof(first,{transactionID:proof.value.transactionID,eventID:`snapshot:${randomUUID()}`}).key)));
    assert.deepEqual(await f.entitlement(member),granted);
    const status=await f.packs.status(member,first.orderID);
    assert.equal(status.fulfillmentRecorded,true);assert.equal(status.unlockedAt,new Date(granted.unlocked_at).toISOString());
    const second=await f.order('byok_unlock',member);
    await f.router.reconcile('stripe',f.proof(second).key);
    assert.deepEqual(await f.entitlement(member),granted);
    const secondStatus=await f.packs.status(member,second.orderID);
    assert.equal(secondStatus.fulfillmentRecorded,false);assert.equal(secondStatus.unlockedAt,null);
  }finally{await f.cleanup();}
});
integration('pending then cancelled unlock never grants and a late purchased event cannot resurrect it',async()=>{
  const f=await fixture();try{
    const member=await f.account(),order=await f.order('byok_unlock',member),pending=f.proof(order,{state:'pending'});
    assert.equal((await f.packs.reconcile('stripe',pending.key)).fulfillmentRecorded,false);
    assert.equal(await f.entitlement(member),undefined);
    const voided=f.proof(order,{transactionID:pending.value.transactionID,state:'voided'});await f.packs.reconcile('stripe',voided.key);
    const late=f.proof(order,{transactionID:pending.value.transactionID});assert.equal((await f.packs.reconcile('stripe',late.key)).state,'voided');
    assert.equal(await f.entitlement(member),undefined);
    assert.equal((await f.db.query('SELECT count(*) FROM ledger')).rows[0].count,'0');
  }finally{await f.cleanup();}
});
integration('unlock refunds revoke only the revoked order grant and never a campaign unlock',async()=>{
  const f=await fixture();try{
    const campaign=await f.account();
    await f.db.query(`INSERT INTO entitlements(account_id,unlocked_at,unlock_source) VALUES($1,now(),'campaign')`,[campaign]);
    const order=await f.order('byok_unlock',campaign),proof=f.proof(order);
    await f.router.reconcile('stripe',proof.key);
    const before=await f.entitlement(campaign);
    assert.equal(before.unlock_source,'campaign');assert.equal(before.unlock_order_id,null);
    await f.router.reconcile('stripe',f.proof(order,{transactionID:proof.value.transactionID,state:'voided'}).key);
    const kept=await f.entitlement(campaign);
    assert.equal(kept.unlock_source,'campaign');assert.ok(kept.unlocked_at);
    assert.equal((await f.packs.status(campaign,order.orderID)).fulfillmentRecorded,false);
  }finally{await f.cleanup();}
});
integration('a purchased unlock refund clears the entitlement and stale purchases cannot restore it',async()=>{
  const f=await fixture();try{
    const member=await f.account(),order=await f.order('byok_unlock',member),purchase=f.proof(order);
    await f.packs.reconcile('stripe',purchase.key);assert.ok(await f.unlockedAt(member));
    const partial=f.proof(order,{transactionID:purchase.value.transactionID,refundedMinor:100});
    await f.packs.reconcile('stripe',partial.key);
    assert.equal(await f.unlockedAt(member),null);
    const status=await f.packs.status(member,order.orderID);
    assert.equal(status.fulfillmentRecorded,false);assert.equal(status.unlockedAt,null);assert.equal(status.state,'purchased');
    await f.packs.reconcile('stripe',purchase.key);assert.equal(await f.unlockedAt(member),null);
    await f.packs.reconcile('stripe',f.proof(order,{transactionID:purchase.value.transactionID,eventID:`snapshot:${randomUUID()}`}).key);
    assert.equal(await f.unlockedAt(member),null);
    assert.equal((await f.db.query('SELECT count(*) FROM ledger')).rows[0].count,'0');
  }finally{await f.cleanup();}
});
integration('partial and full pack refunds are cumulative and stale provider snapshots cannot restore credit',async()=>{
  const f=await fixture();try{
    const member=await f.account(),order=await f.order('token_pack',member),purchase=f.proof(order);await f.packs.reconcile('stripe',purchase.key);
    const partial=f.proof(order,{transactionID:purchase.value.transactionID,refundedMinor:17});
    const partialResult=await f.packs.reconcile('stripe',partial.key);
    assert.equal(partialResult.reversedNanoUSD,refundedAIValue(TOKEN_PACK_CREDIT_NANO,17,order.totalMinor).toString());
    await f.packs.reconcile('stripe',partial.key);await f.packs.reconcile('stripe',purchase.key);
    const full=f.proof(order,{transactionID:purchase.value.transactionID,refundedMinor:order.totalMinor});
    assert.equal((await f.packs.reconcile('stripe',full.key)).reversedNanoUSD,'2160000000');
    assert.equal((await paidAIBalance(f.db,member)).availableNanoUSD,'0');
    const ledger=(await f.db.query("SELECT kind FROM ledger WHERE account_id=$1 ORDER BY created_at, id",[member])).rows.map(row=>row.kind);
    assert.deepEqual(ledger,['purchase','reversal','reversal']);
  }finally{await f.cleanup();}
});
integration('refund while reserved preserves the hold and later pack tops up first repay actual debt',async()=>{
  const f=await fixture();try{
    const member=await f.account(),order=await f.order('token_pack',member),proof=f.proof(order);await f.packs.reconcile('stripe',proof.key);
    const hold=await reserve(f.db,member,randomUUID(),2_000_000_000n);
    await f.packs.reconcile('stripe',f.proof(order,{transactionID:proof.value.transactionID,state:'voided'}).key);
    const before=await paidAIBalance(f.db,member);assert.equal(before.balanceNanoUSD,'0');assert.equal(before.reservedNanoUSD,'2000000000');assert.equal(before.availableNanoUSD,'0');
    await settle(f.db,hold,1_500_000_000n);assert.equal((await paidAIBalance(f.db,member)).balanceNanoUSD,'-1500000000');
    const second=await f.order('token_pack',member);await f.packs.reconcile('stripe',f.proof(second).key);
    assert.equal((await paidAIBalance(f.db,member)).availableNanoUSD,'660000000');
  }finally{await f.cleanup();}
});
integration('sandbox pack purchases cannot fund public conversations or consume real value',async()=>{
  const f=await fixture();try{
    const member=await f.account(),live=await f.order('token_pack',member);await f.packs.reconcile('stripe',f.proof(live).key);
    // A test-environment purchase of the same catalog credits the sandbox only.
    const sandboxPacks=new PackPurchases(f.db,{catalog:[makePackProduct(input('token_pack','test'))],verifiers:[{provider:'stripe',
      environment:'test',merchant:'acct_syntheticpack',verify:async(key:unknown)=>f.evidence.get(key as string)!}],salesEnabled:true});
    const testOrder=await sandboxPacks.createOrder(member,'stripe',f.packProduct.sku,randomUUID());
    const sandboxProof={provider:'stripe' as const,environment:'test' as const,merchant:'acct_syntheticpack',orderID:testOrder.orderID,
      transactionID:`synthetic:${randomUUID()}`,eventID:`snapshot:${randomUUID()}`,providerProduct:f.packProduct.providerProduct,quantity:1,
      currency:'usd',totalMinor:TOKEN_PACK_PRICE_MINOR,state:'purchased' as const,refundedMinor:0};
    f.evidence.set('sandbox',sandboxProof);await sandboxPacks.reconcile('stripe','sandbox');
    assert.equal((await paidAIBalance(f.db,member)).availableNanoUSD,'2160000000');
    f.evidence.set('sandbox-refund',{...sandboxProof,eventID:`snapshot:${randomUUID()}`,state:'voided' as const});
    await sandboxPacks.reconcile('stripe','sandbox-refund');
    assert.equal((await paidAIBalance(f.db,member)).availableNanoUSD,'2160000000');
    const row=(await f.db.query('SELECT balance_nano,sandbox_balance_nano FROM wallets WHERE account_id=$1',[member])).rows[0];
    assert.equal(row.balance_nano,'2160000000');assert.equal(row.sandbox_balance_nano,'0');
  }finally{await f.cleanup();}
});
integration('one provider transaction cannot cross pack, AI value and minute orders',async()=>{
  const f=await fixture();try{
    const member=await f.account(),pack=await f.order('token_pack',member),aiOrder=await f.ai.createOrder(member,'stripe','synthetic-ai',randomUUID()),
      minuteOrder=await f.legacy.createOrder(member,'stripe','historical-synthetic',randomUUID());
    const proof=f.proof(pack);await f.router.reconcile('stripe',proof.key);
    await assert.rejects(f.ai.reconcile('stripe',f.proof(aiOrder,{transactionID:proof.value.transactionID}).key),/purchase_transaction_conflict/);
    await assert.rejects(f.legacy.reconcile('stripe',f.proof(minuteOrder,{transactionID:proof.value.transactionID}).key),/purchase_transaction_conflict/);
    assert.equal((await paidAIBalance(f.db,member)).balanceNanoUSD,'2160000000');
    await assert.rejects(f.packs.reconcile('stripe',f.proof(aiOrder).key),/purchase_entitlement_mismatch/);
  }finally{await f.cleanup();}
});
integration('mismatched evidence is rejected before any pack state changes',async()=>{
  const f=await fixture();try{
    const member=await f.account(),order=await f.order('token_pack',member);
    for (const overrides of [{totalMinor:order.totalMinor+1},{currency:'eur'},{quantity:2},{merchant:'acct_wrong'},{providerProduct:'price_other'}])
      await assert.rejects(f.packs.reconcile('stripe',f.proof(order,overrides).key),/pack_purchase_mismatch|invalid_purchase_evidence/);
    assert.equal((await f.db.query('SELECT count(*) FROM pack_purchase_transactions')).rows[0].count,'0');
    const proof=f.proof(order);await f.packs.reconcile('stripe',proof.key);
    await assert.rejects(f.packs.reconcile('stripe',f.proof(order,{transactionID:proof.value.transactionID,eventID:proof.value.eventID,refundedMinor:1}).key),/purchase_event_conflict/);
    assert.equal((await paidAIBalance(f.db,member)).availableNanoUSD,'2160000000');
  }finally{await f.cleanup();}
});
integration('the router routes pack kinds and reports unmapped orders when packs are absent',async()=>{
  const f=await fixture();try{
    const member=await f.account(),order=await f.order('byok_unlock',member);
    const solo=new PurchaseFulfillmentRouter(f.db,f.legacy,f.ai,[f.verifier]);
    await assert.rejects(solo.reconcile('stripe',f.proof(order).key),/unmapped_purchase/);
    await f.router.reconcile('stripe',[...f.evidence.entries()].find(([,value])=>value.orderID===order.orderID)![0]);
    assert.ok((await f.entitlement(member)).unlocked_at);
    await assert.rejects(f.router.reconcile('stripe',f.proof({orderID:randomUUID(),providerProduct:'price_synthetic_unlock',currency:'usd',totalMinor:699}).key),/unmapped_purchase/);
  }finally{await f.cleanup();}
});
integration('encrypted receipt and delivery retry grant the pack exactly once through the shared worker',async()=>{
  const f=await fixture();try{
    const member=await f.account(),order=await f.order('token_pack',member),proof=f.proof(order);let complete=0,verified=0;
    const vault=new MinuteReceiptVault(f.db,'test',new Map([['test',Buffer.alloc(32,7)]]));
    const adapter={...f.verifier,verify:async()=>{verified++;return proof.value;},complete:async()=>{complete++;if (complete===1) throw new Error('Synthetic acknowledgment failure');}};
    const packs=new PackPurchases(f.db,{verifiers:[adapter]}),legacy=new MinutePurchases(f.db,{verifiers:[adapter]}),
      ai=new AIValuePurchases(f.db,{verifiers:[adapter]});
    const worker=new MinuteDeliveryWorker(f.db,new PurchaseFulfillmentRouter(f.db,legacy,ai,[adapter],packs),[adapter]);
    await vault.save(order.orderID,adapter,proof.value.transactionID);
    assert.deepEqual(await worker.runBatch(1),{processed:1,completed:0,retried:1});
    await vault.schedule(order.orderID);assert.deepEqual(await worker.runBatch(1),{processed:1,completed:1,retried:0});
    assert.equal(verified,2);assert.equal((await paidAIBalance(f.db,member)).availableNanoUSD,'2160000000');
    assert.equal((await f.db.query('SELECT count(*) FROM ledger WHERE account_id=$1',[member])).rows[0].count,'1');
  }finally{await f.cleanup();}
});
integration('the actual runtime role fulfills and refunds pack orders without entitlements-history permission',async()=>{
  const f=await fixture(),role=`pack_runtime_${randomUUID().replaceAll('-','')}`;
  let runtime:ReturnType<typeof connectDatabase>|undefined;
  try{
    const member=await f.account();
    await f.db.query(`CREATE ROLE ${role};GRANT USAGE ON SCHEMA ${f.schema} TO ${role};GRANT SELECT,UPDATE ON accounts TO ${role}`);
    for(const file of ['minute-runtime-grants.sql','minute-purchase-runtime-grants.sql','actual-value-runtime-grants.sql','pack-runtime-grants.sql']){
      const grants=await readFile(new URL(`../operations/${file}`,import.meta.url),'utf8');
      await f.db.query(grants.replaceAll('app_runtime',role));
    }
    const url=new URL(f.url);url.searchParams.set('options',`-c search_path=${f.schema} -c role=${role}`);
    runtime=connectDatabase(url.toString());
    const packs=new PackPurchases(runtime,{catalog:[f.packProduct,f.unlockProduct],verifiers:[f.verifier],salesEnabled:true});
    const order=await packs.createOrder(member,'stripe',f.packProduct.sku,randomUUID()),proof=f.proof(order);
    const purchase=await packs.reconcile('stripe',proof.key);assert.equal(purchase.grantedNanoUSD,'2160000000');
    assert.equal((await paidAIBalance(runtime,member)).balanceNanoUSD,'2160000000');
    const refunded=await packs.reconcile('stripe',f.proof(order,{transactionID:proof.value.transactionID,state:'voided'}).key);
    assert.equal(refunded.reversedNanoUSD,'2160000000');
    assert.equal((await paidAIBalance(runtime,member)).balanceNanoUSD,'0');
    // The runtime role cannot rewrite the immutable quote, the event journal or another order's unlock.
    await assert.rejects(runtime.query(`UPDATE pack_purchase_quotes SET credit_nano=1 WHERE order_id=$1`,[order.orderID]),/permission denied/);
    await assert.rejects(runtime.query(`DELETE FROM entitlements WHERE account_id=$1`,[member]),/permission denied/);
    await assert.rejects(runtime.query(`UPDATE pack_purchase_transactions SET credit_nano=1 WHERE order_id=$1`,[order.orderID]),/permission denied/);
  }finally{
    await runtime?.end();
    if((await f.db.query('SELECT 1 FROM pg_roles WHERE rolname=$1',[role])).rowCount){await f.db.query(`DROP OWNED BY ${role};DROP ROLE ${role}`);}
    await f.cleanup();
  }
});
