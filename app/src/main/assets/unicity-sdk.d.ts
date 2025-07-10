// Type definitions for the Unicity SDK bundle
// This file defines the types available in the global 'unicity' object

declare module '@unicitylabs/state-transition-sdk' {
  export interface ISerializable {
    toJSON(): any;
  }

  export class TokenId implements ISerializable {
    static create(bytes: Uint8Array): TokenId;
    toJSON(): string;
    readonly bytes: Uint8Array;
  }

  export class TokenType implements ISerializable {
    static create(bytes: Uint8Array): TokenType;
    toJSON(): string;
    toCBOR(): any;
    readonly bytes: Uint8Array;
  }

  export class CoinId {
    constructor(bytes: Uint8Array);
    readonly bytes: Uint8Array;
  }

  export class TokenCoinData {
    static create(coins: Array<[CoinId, bigint]>): TokenCoinData;
    readonly coins: Map<CoinId, bigint>;
  }

  export class DirectAddress {
    static create(reference: Uint8Array): Promise<DirectAddress>;
    static fromJSON(json: string): Promise<DirectAddress>;
    toJSON(): string;
    readonly reference: Uint8Array;
  }

  export class MaskedPredicate {
    static create(
      tokenId: TokenId,
      tokenType: TokenType,
      signingService: import('@unicitylabs/commons').SigningService,
      hashAlgorithm: import('@unicitylabs/commons').HashAlgorithm,
      nonce: Uint8Array
    ): Promise<MaskedPredicate>;
    
    static calculateReference(
      tokenType: TokenType,
      algorithm: string,
      publicKey: Uint8Array,
      hashAlgorithm: import('@unicitylabs/commons').HashAlgorithm,
      nonce: Uint8Array
    ): Promise<Uint8Array>;
    
    readonly reference: Uint8Array;
    readonly nonce: Uint8Array;
    isOwner(publicKey: Uint8Array): boolean;
  }

  export class TokenState {
    static create(
      unlockPredicate: MaskedPredicate,
      data: Uint8Array | null
    ): Promise<TokenState>;
    
    readonly hash: import('@unicitylabs/commons').DataHash;
    readonly unlockPredicate: MaskedPredicate;
    readonly data: Uint8Array | null;
  }

  export class MintTransactionData<T extends ISerializable | null> {
    static create(
      tokenId: TokenId,
      tokenType: TokenType,
      data: Uint8Array,
      coinData: TokenCoinData | null,
      recipient: string,
      salt: Uint8Array,
      nameTag: T,
      tag: Uint8Array | null
    ): Promise<MintTransactionData<T>>;
    
    readonly hash: import('@unicitylabs/commons').DataHash;
    readonly tokenId: TokenId;
    readonly tokenType: TokenType;
    readonly data: Uint8Array;
    readonly coinData: TokenCoinData | null;
    readonly recipient: string;
  }

  export class TransactionData {
    static create(
      state: TokenState,
      recipient: string,
      salt: Uint8Array,
      dataHash: import('@unicitylabs/commons').DataHash | null,
      message: Uint8Array | null,
      nametagTokens?: any[]
    ): Promise<TransactionData>;
    
    readonly hash: import('@unicitylabs/commons').DataHash;
    readonly sourceState: TokenState;
    readonly recipient: string;
  }

  export class Commitment<T extends TransactionData | MintTransactionData<any>> {
    static create(
      transactionData: T,
      signingService: import('@unicitylabs/commons').SigningService
    ): Promise<Commitment<T>>;
    
    readonly requestId: import('@unicitylabs/commons').RequestId;
    readonly transactionData: T;
    readonly authenticator: import('@unicitylabs/commons').Authenticator;
  }

  export class Transaction<T extends TransactionData | MintTransactionData<any>> {
    constructor(
      data: T,
      inclusionProof: import('@unicitylabs/commons').InclusionProof
    );
    
    readonly data: T;
    readonly inclusionProof: import('@unicitylabs/commons').InclusionProof;
  }

  export class Token<T extends Transaction<MintTransactionData<any>>> {
    constructor(
      state: TokenState,
      genesis: T,
      transactions: Transaction<TransactionData>[],
      nametagTokens: any[],
      version?: string
    );
    
    readonly id: TokenId;
    readonly type: TokenType;
    readonly state: TokenState;
    readonly genesis: T;
    readonly transactions: Transaction<TransactionData>[];
    readonly data: Uint8Array;
    readonly coins: TokenCoinData | null;
    readonly nametagTokens: any[];
  }

  export interface IAggregatorClient {
    submitTransaction(
      requestId: import('@unicitylabs/commons').RequestId,
      hash: import('@unicitylabs/commons').DataHash,
      authenticator: import('@unicitylabs/commons').Authenticator
    ): Promise<import('@unicitylabs/commons').SubmitCommitmentResponse>;
    
    getInclusionProof(
      requestId: import('@unicitylabs/commons').RequestId
    ): Promise<import('@unicitylabs/commons').InclusionProof>;
  }

  export class AggregatorClient implements IAggregatorClient {
    constructor(url: string);
    
    submitTransaction(
      requestId: import('@unicitylabs/commons').RequestId,
      hash: import('@unicitylabs/commons').DataHash,
      authenticator: import('@unicitylabs/commons').Authenticator
    ): Promise<import('@unicitylabs/commons').SubmitCommitmentResponse>;
    
    getInclusionProof(
      requestId: import('@unicitylabs/commons').RequestId
    ): Promise<import('@unicitylabs/commons').InclusionProof>;
  }

  export class StateTransitionClient {
    constructor(client: IAggregatorClient);
    
    readonly client: IAggregatorClient;
    
    submitMintTransaction<T extends MintTransactionData<ISerializable | null>>(
      transactionData: T
    ): Promise<Commitment<T>>;
    
    submitCommitment(
      commitment: Commitment<TransactionData>
    ): Promise<import('@unicitylabs/commons').SubmitCommitmentResponse>;
    
    createTransaction<T extends TransactionData | MintTransactionData<ISerializable | null>>(
      commitment: Commitment<T>,
      inclusionProof: import('@unicitylabs/commons').InclusionProof
    ): Promise<Transaction<T>>;
    
    finishTransaction<T extends Transaction<MintTransactionData<ISerializable | null>>>(
      token: Token<T>,
      state: TokenState,
      transaction: Transaction<TransactionData>,
      nametagTokens?: any[]
    ): Promise<Token<T>>;
    
    getInclusionProof(
      commitment: Commitment<TransactionData | MintTransactionData<ISerializable | null>>
    ): Promise<import('@unicitylabs/commons').InclusionProof>;
  }

  export class CommitmentJsonSerializer {
    constructor(predicateFactory: PredicateJsonFactory);
    
    static serialize(commitment: Commitment<any>): any;
    
    deserialize(
      tokenId: TokenId,
      tokenType: TokenType,
      json: any
    ): Promise<Commitment<TransactionData>>;
  }

  export class TokenJsonSerializer {
    constructor(predicateJsonFactory: PredicateJsonFactory);
  }

  export class TransactionJsonSerializer {
    constructor(predicateFactory: PredicateJsonFactory);
    
    static serialize(transaction: Transaction<any>): any;
    
    deserialize(
      tokenId: TokenId,
      tokenType: TokenType,
      json: any
    ): Promise<Transaction<TransactionData>>;
  }

  export class TokenFactory {
    constructor(serializer: TokenJsonSerializer);
    
    create(json: any): Promise<Token<any>>;
  }

  export class PredicateJsonFactory {
    constructor();
  }

  export function waitInclusionProof(
    client: StateTransitionClient,
    commitment: Commitment<TransactionData | MintTransactionData<ISerializable | null>>,
    signal?: AbortSignal,
    interval?: number
  ): Promise<import('@unicitylabs/commons').InclusionProof>;
}

declare module '@unicitylabs/commons' {
  export class DataHash {
    readonly algorithm: HashAlgorithm;
    readonly bytes: Uint8Array;
    
    toCBOR(): any;
    equals(other: DataHash): boolean;
    toString(): string;
  }

  export class DataHasher {
    constructor(algorithm: HashAlgorithm);
    
    update(data: Uint8Array): DataHasher;
    digest(): Promise<DataHash>;
  }

  export enum HashAlgorithm {
    SHA256 = 'SHA256'
  }

  export interface ISigningService {
    readonly algorithm: string;
    readonly publicKey: Uint8Array;
    sign(data: Uint8Array): Promise<Signature>;
  }

  export class SigningService implements ISigningService {
    static createFromSecret(
      secret: Uint8Array,
      nonce: Uint8Array
    ): Promise<SigningService>;
    
    readonly algorithm: string;
    readonly publicKey: Uint8Array;
    
    sign(data: Uint8Array): Promise<Signature>;
  }

  export interface ISignature {
    readonly bytes: Uint8Array;
  }

  export class Signature implements ISignature {
    readonly bytes: Uint8Array;
  }

  export class RequestId {
    static create(
      publicKey: Uint8Array,
      stateHash: DataHash
    ): Promise<RequestId>;
    
    readonly bytes: Uint8Array;
  }

  export class Authenticator {
    readonly publicKey: Uint8Array;
    readonly signature: Signature;
    readonly stateHash: DataHash;
  }

  export class InclusionProof {
    readonly authenticator: Authenticator | null;
    readonly transactionHash: DataHash | null;
    
    verify(requestId: RequestId): Promise<InclusionProofVerificationStatus>;
  }

  export enum InclusionProofVerificationStatus {
    OK = 'OK',
    PATH_NOT_INCLUDED = 'PATH_NOT_INCLUDED'
  }

  export class SubmitCommitmentResponse {
    readonly status: SubmitCommitmentStatus;
  }

  export enum SubmitCommitmentStatus {
    SUCCESS = 'SUCCESS',
    FAILURE = 'FAILURE'
  }
}

// Global unicity object definition
declare global {
  const unicity: {
    StateTransitionClient: typeof import('@unicitylabs/state-transition-sdk').StateTransitionClient;
    AggregatorClient: typeof import('@unicitylabs/state-transition-sdk').AggregatorClient;
    Commitment: typeof import('@unicitylabs/state-transition-sdk').Commitment;
    CommitmentJsonSerializer: typeof import('@unicitylabs/state-transition-sdk').CommitmentJsonSerializer;
    TokenFactory: typeof import('@unicitylabs/state-transition-sdk').TokenFactory;
    TokenJsonSerializer: typeof import('@unicitylabs/state-transition-sdk').TokenJsonSerializer;
    PredicateJsonFactory: typeof import('@unicitylabs/state-transition-sdk').PredicateJsonFactory;
    RequestId: typeof import('@unicitylabs/commons').RequestId;
    MintTransactionData: typeof import('@unicitylabs/state-transition-sdk').MintTransactionData;
    TokenId: typeof import('@unicitylabs/state-transition-sdk').TokenId;
    TokenType: typeof import('@unicitylabs/state-transition-sdk').TokenType;
    DirectAddress: typeof import('@unicitylabs/state-transition-sdk').DirectAddress;
    SigningService: typeof import('@unicitylabs/commons').SigningService;
    HashAlgorithm: typeof import('@unicitylabs/commons').HashAlgorithm;
    MaskedPredicate: typeof import('@unicitylabs/state-transition-sdk').MaskedPredicate;
    TokenCoinData: typeof import('@unicitylabs/state-transition-sdk').TokenCoinData;
    CoinId: typeof import('@unicitylabs/state-transition-sdk').CoinId;
    Token: typeof import('@unicitylabs/state-transition-sdk').Token;
    TokenState: typeof import('@unicitylabs/state-transition-sdk').TokenState;
    Transaction: typeof import('@unicitylabs/state-transition-sdk').Transaction;
    TransactionData: typeof import('@unicitylabs/state-transition-sdk').TransactionData;
    TransactionJsonSerializer: typeof import('@unicitylabs/state-transition-sdk').TransactionJsonSerializer;
    SubmitCommitmentStatus: typeof import('@unicitylabs/commons').SubmitCommitmentStatus;
    waitInclusionProof: typeof import('@unicitylabs/state-transition-sdk').waitInclusionProof;
  };
}

export {};