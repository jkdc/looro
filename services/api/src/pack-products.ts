import { createHash, randomUUID } from 'node:crypto';
import { transaction, type Database } from './db.js';
import { appendEntry, lockWallet } from './ledger.js';
import { ServiceError } from './errors.js';
import { refundedAIValue } from './ai-value-purchases.js';
import type { MinutePurchases, MinutePurchaseVerifier, PurchaseProvider, PurchaseScope,
  VerifiedMinutePurchase } from './minute-purchases.js';

const uuid = /^[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}$/i;
const identifier = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,199}$/;
const money = (value: unknown, positive=false): value is number => typeof value==='number' && Number.isSafeInteger(value) && value>=(positive?1:0) && value<=100_000_000;
const hash = (value: string) => createHash('sha256').update(value).digest('hex');
const scopeKey = (scope: PurchaseScope) => JSON.stringify([scope.provider,scope.environment,scope.merchant]);
const productKey = (scope: PurchaseScope, sku: string) => JSON.stringify([scopeKey(scope),sku]);
export const PACK_ENTITLEMENT_KINDS = ['byok_unlock','token_pack'] as const;
export type PackEntitlementKind = typeof PACK_ENTITLEMENT_KINDS[number];
/** Approved launch prices and credit (Joaquín, 2026-09): constants, not configuration. */
export const PACK_CURRENCY = 'usd';
export const BYOK_UNLOCK_PRICE_MINOR = 699;
export const TOKEN_PACK_PRICE_MINOR = 499;
export const TOKEN_PACK_CREDIT_NANO = 2_160_000_000n;
function scopeValid(scope: PurchaseScope) {
  return scope && ['stripe','play'].includes(scope.provider) && ['test','live'].includes(scope.environment) &&
    typeof scope.merchant==='string' && identifier.test(scope.merchant);
}
export interface PackProductInput extends PurchaseScope {
  sku: string; providerProduct: string; currency: string; totalMinor: number; entitlementKind: PackEntitlementKind;
  creditNanoUSD: string;
}
export interface PackProduct extends PurchaseScope {
  readonly sku: string; readonly providerProduct: string; readonly currency: string; readonly totalMinor: number;
  readonly entitlementKind: PackEntitlementKind; readonly creditNanoUSD: string;
}
export interface PackOrder extends PackProduct { readonly orderID: string }
export interface PackPurchaseStatus {
  readonly orderID: string; readonly entitlementKind: PackEntitlementKind;
  readonly state: 'created'|'pending'|'purchased'|'voided';
  readonly grantedNanoUSD: string; readonly reversedNanoUSD: string; readonly reversalOutstandingNanoUSD: string;
  readonly fulfillmentRecorded: boolean;
  /** For the unlock: the still-active unlock timestamp this order granted, if any. */
  readonly unlockedAt: string | null;
}

/** Catalog entries carry no arithmetic of their own: kind, price and credit are
 * server constants, so a tampered catalog cannot sell a different product. */
export function makePackProduct(input: PackProductInput): Readonly<PackProduct> {
  const kind = input?.entitlementKind;
  const price = kind==='byok_unlock' ? BYOK_UNLOCK_PRICE_MINOR : kind==='token_pack' ? TOKEN_PACK_PRICE_MINOR : 0;
  const credit = kind==='token_pack' ? TOKEN_PACK_CREDIT_NANO.toString() : '0';
  if (!scopeValid(input) || !kind || !PACK_ENTITLEMENT_KINDS.includes(kind) ||
    typeof input.sku!=='string' || input.sku.length>128 || !identifier.test(input.sku) ||
    typeof input.providerProduct!=='string' || !identifier.test(input.providerProduct) ||
    input.currency!==PACK_CURRENCY || !money(input.totalMinor,true) || input.totalMinor!==price ||
    input.creditNanoUSD!==credit) throw new ServiceError('invalid_pack_product');
  return Object.freeze({provider:input.provider,environment:input.environment,merchant:input.merchant,sku:input.sku,
    providerProduct:input.providerProduct,currency:input.currency,totalMinor:input.totalMinor,
    entitlementKind:kind,creditNanoUSD:credit});
}
function validateProduct(candidate: PackProductInput): Readonly<PackProduct> {
  try {
    const canonical=makePackProduct(candidate);
    for (const key of Object.keys(canonical) as (keyof PackProduct)[])
      if (canonical[key]!==candidate[key]) throw new Error();
    if (Object.keys(candidate).length!==Object.keys(canonical).length) throw new Error();
    return canonical;
  } catch { throw new ServiceError('invalid_pack_product'); }
}
function mappedOrder(row: any): PackOrder {
  return {orderID:row.id,provider:row.provider,environment:row.environment,merchant:row.merchant,sku:row.sku,
    providerProduct:row.provider_product,currency:row.currency,totalMinor:Number(row.total_minor),
    entitlementKind:row.entitlement_kind,creditNanoUSD:BigInt(row.credit_nano).toString()};
}
const purchaseStatus=(id:string,kind:PackEntitlementKind,row?:any,unlockedAt?:Date|string|null):PackPurchaseStatus=>({
  orderID:id,entitlementKind:kind,state:row?.state??'created',grantedNanoUSD:String(row?.granted_nano??0),
  reversedNanoUSD:String(row?.reversed_nano??0),reversalOutstandingNanoUSD:'0',
  fulfillmentRecorded:kind==='byok_unlock'?Boolean(unlockedAt):BigInt(row?.granted_nano??0)>0n,
  unlockedAt:kind==='byok_unlock'&&unlockedAt?new Date(unlockedAt).toISOString():null});
function evidenceValid(e:VerifiedMinutePurchase,scope:PurchaseScope) {
  if (!scopeValid(e) || scopeKey(e)!==scopeKey(scope) || !uuid.test(e.orderID) ||
    typeof e.transactionID!=='string' || !/^[\x21-\x7e]{1,4096}$/.test(e.transactionID) ||
    typeof e.eventID!=='string' || !/^[\x21-\x7e]{1,4096}$/.test(e.eventID) || !identifier.test(e.providerProduct) || e.quantity!==1 ||
    !/^[a-z]{3}$/.test(e.currency) || !money(e.totalMinor,true) || !money(e.refundedMinor) || e.refundedMinor>e.totalMinor ||
    !['pending','purchased','voided'].includes(e.state) || (e.state==='pending' && e.refundedMinor!==0)) throw new ServiceError('invalid_purchase_evidence',502);
}

/** Pack catalog. All real entitlements come from server-verified provider evidence
 * through the same reconciliation machinery as minutes and AI value. */
export class PackPurchases {
  readonly #catalog=new Map<string,Readonly<PackProduct>>();
  readonly #verifiers=new Map<PurchaseProvider,MinutePurchaseVerifier>();
  readonly #salesEnabled:boolean;
  constructor(readonly db:Database, options:{catalog?:readonly PackProductInput[];verifiers?:readonly MinutePurchaseVerifier[];salesEnabled?:boolean}={}) {
    this.#salesEnabled=options.salesEnabled===true;
    for (const verifier of options.verifiers??[]) {
      if (!scopeValid(verifier) || typeof verifier.verify!=='function' || this.#verifiers.has(verifier.provider)) throw new ServiceError('invalid_purchase_verifier');
      this.#verifiers.set(verifier.provider,Object.freeze({provider:verifier.provider,environment:verifier.environment,merchant:verifier.merchant,verify:verifier.verify.bind(verifier)}));
    }
    const bindings=new Set<string>();
    for (const candidate of options.catalog??[]) {
      const product=validateProduct(candidate),key=productKey(product,product.sku),binding=JSON.stringify([scopeKey(product),product.providerProduct,product.currency]);
      const verifier=this.#verifiers.get(product.provider);
      if (this.#catalog.has(key) || bindings.has(binding) || !verifier || scopeKey(product)!==scopeKey(verifier)) throw new ServiceError('invalid_pack_catalog');
      this.#catalog.set(key,product);bindings.add(binding);
    }
  }
  /** The order route dispatches on this before createOrder, independent of the sales gate. */
  hasProduct(provider:PurchaseProvider,sku:string):boolean {
    const verifier=this.#verifiers.get(provider);
    return !!verifier && this.#catalog.has(productKey(verifier,sku));
  }
  products(provider:PurchaseProvider):readonly Readonly<PackProduct>[] {
    return this.#salesEnabled?[...this.#catalog.values()].filter(product=>product.provider===provider):[];
  }
  async createOrder(accountID:string,provider:PurchaseProvider,sku:string,idempotencyKey:string):Promise<PackOrder> {
    if (!this.#salesEnabled) throw new ServiceError('pack_purchases_unavailable',503);
    if (!uuid.test(accountID) || typeof sku!=='string' || typeof idempotencyKey!=='string' || !/^[A-Za-z0-9._:-]{8,128}$/.test(idempotencyKey)) throw new ServiceError('invalid_pack_order');
    const verifier=this.#verifiers.get(provider); if (!verifier) throw new ServiceError('pack_purchases_unavailable',503);
    return transaction(this.db,async sql=>{
      const wallet=await lockWallet(sql,accountID,true);
      if ((await sql.query('SELECT is_guest FROM accounts WHERE id=$1',[accountID])).rows[0].is_guest) throw new ServiceError('purchase_requires_account',403);
      // Pack credit is spendable cash: no new order on top of unreviewed provenance.
      if (!wallet.cashProvenanceVerified) throw new ServiceError('cash_balance_reconciliation_required',409);
      const prior=(await sql.query(`SELECT o.*,q.kind,q.credit_nano FROM minute_purchase_orders o
        LEFT JOIN pack_purchase_quotes q ON q.order_id=o.id WHERE o.account_id=$1 AND o.idempotency_key=$2`,[accountID,idempotencyKey])).rows[0];
      if (prior) {
        if (!PACK_ENTITLEMENT_KINDS.includes(prior.entitlement_kind) || prior.kind!==prior.entitlement_kind ||
          prior.provider!==provider || prior.sku!==sku || prior.environment!==verifier.environment || prior.merchant!==verifier.merchant)
          throw new ServiceError('idempotency_conflict',409);
        return mappedOrder(prior);
      }
      const product=this.#catalog.get(productKey(verifier,sku)); if (!product) throw new ServiceError('pack_product_unavailable',503);
      // The pack's ledger entries pin the active rate version so a later rate
      // change never reinterprets an already-paid balance.
      let rateVersion: string | null = null;
      if (product.entitlementKind==='token_pack') {
        rateVersion=(await sql.query('SELECT id FROM rate_versions ORDER BY valid_from DESC LIMIT 1')).rows[0]?.id ?? null;
        if (!rateVersion) throw new ServiceError('proxy_unavailable',503);
      }
      const id=randomUUID();
      await sql.query(`INSERT INTO minute_purchase_orders(id,account_id,idempotency_key,provider,environment,merchant,sku,provider_product,currency,total_minor,allowance_ms,entitlement_kind)
        VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,NULL,$11)`,[id,accountID,idempotencyKey,provider,product.environment,product.merchant,sku,product.providerProduct,product.currency,product.totalMinor,product.entitlementKind]);
      await sql.query(`INSERT INTO pack_purchase_quotes(order_id,kind,credit_nano,rate_version) VALUES($1,$2,$3,$4)`,
        [id,product.entitlementKind,BigInt(product.creditNanoUSD).toString(),rateVersion]);
      return {...product,orderID:id};
    });
  }
  async status(accountID:string,orderID:string):Promise<PackPurchaseStatus> {
    if (!uuid.test(accountID) || !uuid.test(orderID)) throw new ServiceError('purchase_not_found',404);
    const found=(await this.db.query(`SELECT p.*,e.unlocked_at,q.kind FROM minute_purchase_orders o JOIN accounts a ON a.id=o.account_id
      JOIN pack_purchase_quotes q ON q.order_id=o.id LEFT JOIN pack_purchase_transactions p ON p.order_id=o.id
      LEFT JOIN entitlements e ON e.account_id=o.account_id AND e.unlock_order_id=o.id
      WHERE o.id=$1 AND o.account_id=$2 AND o.entitlement_kind IN ('byok_unlock','token_pack') AND a.deleted_at IS NULL`,[orderID,accountID])).rows[0];
    if (!found) throw new ServiceError('purchase_not_found',404);
    return purchaseStatus(orderID,found.kind,found.order_id?found:undefined,found.unlocked_at);
  }
  async reconcile(provider:PurchaseProvider,input:unknown):Promise<PackPurchaseStatus> {
    const verifier=this.#verifiers.get(provider); if (!verifier) throw new ServiceError('purchase_verification_unavailable',503);
    let verified:VerifiedMinutePurchase;
    try {verified=await verifier.verify(input);}catch {throw new ServiceError('purchase_verification_failed',502);}
    return this.applyVerifiedEvidence(provider,verified);
  }
  /** Server-internal: callers must use the configured provider verifier or shared fulfillment router. */
  async applyVerifiedEvidence(provider:PurchaseProvider,verified:VerifiedMinutePurchase):Promise<PackPurchaseStatus> {
    const verifier=this.#verifiers.get(provider); if (!verifier) throw new ServiceError('purchase_verification_unavailable',503);
    evidenceValid(verified,verifier);
    const evidence={provider:verified.provider,environment:verified.environment,merchant:verified.merchant,orderID:verified.orderID.toLowerCase(),
      transactionHash:hash(verified.transactionID),eventHash:hash(verified.eventID),providerProduct:verified.providerProduct,currency:verified.currency,
      totalMinor:verified.totalMinor,state:verified.state,refundedMinor:verified.refundedMinor};
    const evidenceHash=hash(JSON.stringify(evidence));
    return transaction(this.db,async sql=>{
      // Serialize each provider transaction and event before locking the owner's wallet.
      const locks=[`minute-purchase-transaction:${scopeKey(evidence)}:${evidence.transactionHash}`,`minute-purchase-event:${scopeKey(evidence)}:${evidence.eventHash}`].sort();
      for (const key of locks) await sql.query('SELECT pg_advisory_xact_lock(hashtextextended($1,0))',[key]);
      const order=(await sql.query(`SELECT o.*,q.kind,q.credit_nano,q.rate_version FROM minute_purchase_orders o
        JOIN pack_purchase_quotes q ON q.order_id=o.id WHERE o.id=$1`,[evidence.orderID])).rows[0];
      if (!order || !PACK_ENTITLEMENT_KINDS.includes(order.entitlement_kind) || order.kind!==order.entitlement_kind) throw new ServiceError('purchase_entitlement_mismatch',409);
      if (order.provider!==provider || order.environment!==evidence.environment || order.merchant!==evidence.merchant || order.provider_product!==evidence.providerProduct ||
        order.currency!==evidence.currency || Number(order.total_minor)!==evidence.totalMinor) throw new ServiceError('pack_purchase_mismatch',409);
      // One provider transaction can only ever fund one entitlement kind.
      if ((await sql.query(`SELECT 1 FROM minute_purchase_transactions WHERE provider=$1 AND environment=$2 AND merchant=$3 AND transaction_hash=$4`,
        [provider,evidence.environment,evidence.merchant,evidence.transactionHash])).rowCount ||
        (await sql.query(`SELECT 1 FROM ai_value_purchase_transactions WHERE provider=$1 AND environment=$2 AND merchant=$3 AND transaction_hash=$4`,
        [provider,evidence.environment,evidence.merchant,evidence.transactionHash])).rowCount) throw new ServiceError('purchase_transaction_conflict',409);
      await lockWallet(sql,order.account_id);
      const duplicate=(await sql.query(`SELECT evidence_hash FROM minute_purchase_events WHERE provider=$1 AND environment=$2 AND merchant=$3 AND event_hash=$4`,
        [provider,evidence.environment,evidence.merchant,evidence.eventHash])).rows[0];
      if (duplicate && duplicate.evidence_hash!==evidenceHash) throw new ServiceError('purchase_event_conflict',409);
      const rows=(await sql.query(`SELECT * FROM pack_purchase_transactions WHERE order_id=$1 OR
        (provider=$2 AND environment=$3 AND merchant=$4 AND transaction_hash=$5) FOR UPDATE`,[evidence.orderID,provider,evidence.environment,evidence.merchant,evidence.transactionHash])).rows;
      if (rows.some(row=>row.order_id!==evidence.orderID || row.transaction_hash!==evidence.transactionHash)) throw new ServiceError('purchase_transaction_conflict',409);
      let purchase=rows[0];
      if (!purchase) purchase=(await sql.query(`INSERT INTO pack_purchase_transactions(order_id,account_id,provider,environment,merchant,transaction_hash,state,credit_nano)
        VALUES($1,$2,$3,$4,$5,$6,'pending',$7) RETURNING *`,[evidence.orderID,order.account_id,provider,evidence.environment,evidence.merchant,evidence.transactionHash,order.credit_nano])).rows[0];
      if (!duplicate) {
        const state=evidence.state==='voided'||purchase.state==='voided'?'voided':evidence.state==='purchased'||purchase.state==='purchased'?'purchased':'pending';
        const credit=BigInt(order.credit_nano),granted=BigInt(purchase.granted_nano);
        if (order.entitlement_kind==='token_pack') {
          if (state==='purchased' && granted===0n) await appendEntry(sql,order.account_id,`pack-purchase:${order.id}`,'purchase',credit,0n,order.rate_version,
            order.environment==='test'?credit:0n);
        } else if (state==='purchased') {
          // Set-once: the first verified purchase wins; replays and later orders are no-ops.
          await sql.query(`INSERT INTO entitlements(account_id,unlocked_at,unlock_source,unlock_order_id) VALUES($1,now(),$2,$3)
            ON CONFLICT (account_id) DO NOTHING`,[order.account_id,provider,order.id]);
        }
        const refund=Math.max(Number(purchase.refunded_minor),evidence.refundedMinor);
        if (order.entitlement_kind==='token_pack') {
          // Mirror AI value: reverse the original credit's proportion of the refund, rounded up once.
          const target=state==='voided'?credit:refundedAIValue(credit,refund,Number(order.total_minor));
          const effectiveGrant=state==='purchased'?credit:granted;
          const reversal=target<effectiveGrant?target:effectiveGrant;
          const delta=reversal-BigInt(purchase.reversed_nano);
          if (delta>0n) await appendEntry(sql,order.account_id,`pack-refund:${order.id}:${reversal}`,'reversal',-delta,0n,order.rate_version,
            order.environment==='test'?-delta:0n);
          await sql.query(`UPDATE pack_purchase_transactions SET state=$2,granted_nano=$3,refunded_minor=$4,reversed_nano=$5,updated_at=now() WHERE order_id=$1`,
            [order.id,state,effectiveGrant.toString(),refund,reversal.toString()]);
        } else {
          // A binary entitlement revokes on any money returned, mirroring the
          // minutes' round-up; only this order's own grant is ever cleared.
          if (state==='voided' || refund>0) await sql.query(`UPDATE entitlements SET unlocked_at=NULL,unlock_source=NULL,unlock_order_id=NULL
            WHERE account_id=$1 AND unlock_order_id=$2`,[order.account_id,order.id]);
          await sql.query(`UPDATE pack_purchase_transactions SET state=$2,refunded_minor=$3,updated_at=now() WHERE order_id=$1`,[order.id,state,refund]);
        }
        await sql.query(`INSERT INTO minute_purchase_events(id,order_id,provider,environment,merchant,event_hash,evidence_hash,state,refunded_minor)
          VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9)`,[randomUUID(),order.id,provider,evidence.environment,evidence.merchant,evidence.eventHash,evidenceHash,evidence.state,evidence.refundedMinor]);
      }
      const row=(await sql.query(`SELECT p.*,e.unlocked_at FROM pack_purchase_transactions p
        LEFT JOIN entitlements e ON e.account_id=p.account_id AND e.unlock_order_id=p.order_id WHERE p.order_id=$1`,[order.id])).rows[0];
      return purchaseStatus(order.id,order.kind,row,row?.unlocked_at);
    });
  }
}
