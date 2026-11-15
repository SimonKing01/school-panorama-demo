/** 本体平台类型定义：字段全部来自 /model/types，禁止按业务类型写专用分支。 */

export interface FieldDef {
  name: string
  displayName: string
  type: string
  derived?: boolean
  origin?: string
  provider?: string
  resolver?: string
  from?: string
}

export interface RelationTypeDef {
  id: string
  forwardName: string
  reverseName: string
  fromType: string
  toType: string
  cardinality: string
}

export interface TypeDef {
  id: string
  displayName: string
  description: string
  titleTemplate: string
  listFields: FieldDef[]
  detailFields: FieldDef[]
  searchableFields: string[]
  filterableFields: string[]
  derivedProperties: { name: string; provider?: string | null; resolver?: string | null }[]
  dynamicResolvers: string[]
  relations: RelationTypeDef[]
  sourceMapping: { sourceId: string; sourceTable: string; sourcePk: string }
}

export interface ModelTypesData {
  mappingVersion: string
  types: TypeDef[]
}

export interface DynamicResult {
  key: string
  value: unknown
  basis: string
  inputs: string[]
  queryTime: string
}

export interface SourceSummary {
  sourceId?: string
  sourceTable?: string
  sourcePk?: string
  sourceCode?: string
  sourceHash?: string
  mappingVersion?: string
}

/** 列表接口 items[].attributes 是字典（03 契约 §2.6）。 */
export interface ObjectRow {
  globalId: string
  type: string
  title: string
  attributes: Record<string, unknown>
  dynamicResults: DynamicResult[]
  sourceSummary: SourceSummary
}

export interface ObjectListData {
  type?: string
  page: number
  size: number
  total: number
  items: ObjectRow[]
}

export interface SearchItem {
  globalId: string
  type: string
  title: string
  matchedField: string
  highlight: string
  attributes: Record<string, unknown>
  sourceSummary: SourceSummary
}

export interface SearchData {
  q: string
  total: number
  items: SearchItem[]
}

/** 详情接口 attributes 是数组（03 契约 §2.6），origin 区分 SOURCE / DERIVED。 */
export interface FieldValue {
  name: string
  displayName: string
  value: unknown
  origin: string
  provider?: string | null
  resolver?: string | null
  from?: string | null
}

export interface NamedObject {
  globalId: string
  title: string
}

/** 对象详情：attributes 是数组（含 origin=SOURCE/DERIVED），与列表的字典结构不同。 */
export interface ObjectDetail {
  globalId: string
  type: string
  title: string
  mappingVersion: string | null
  attributes: FieldValue[]
  dynamicResults: DynamicResult[]
  relations: { forward: RelationView[]; reverse: RelationView[] }
  recentChanges: ChangeItem[]
  source: SourceSummary
  backToBusinessUrl: string
}

export interface RelationView {
  relationId: string
  relationType: string
  displayName: string
  from: NamedObject
  to: NamedObject
  reason: string
  generationKind?: string | null
  source: { sourceTable?: string | null; sourcePk?: string | null; sourceHash?: string | null }
  mappingVersion?: string | null
  validFrom?: string | null
  validTo?: string | null
  active: boolean
  appliedRevision?: number
}

export interface RelationListData {
  globalId: string
  depth: number
  page: number
  size: number
  total: number
  items: RelationView[]
}

export interface GraphNode {
  globalId: string
  type: string
  title: string
  layer: number
}

export interface GraphEdge {
  relationId: string
  relationType: string
  from: string
  to: string
  active: boolean
  label: string
  reason: string
  basis: string
}

export interface GraphData {
  center: string
  depth: number
  limit: number
  page: number
  truncated: boolean
  nodes: GraphNode[]
  edges: GraphEdge[]
}

export interface DiffEntry {
  field: string
  label: string
  before: unknown
  after: unknown
}

export interface ImpactEntry {
  globalId: string
  title?: string
  role: string
  basis: string
}

export interface ChangeItem {
  changeId: number
  eventId?: string | null
  origin: string
  selfObject: NamedObject
  changeKind: string
  diff: DiffEntry[]
  relationsAdded: Record<string, unknown>[]
  relationsRemoved: Record<string, unknown>[]
  impact: ImpactEntry[]
  source: Record<string, unknown>
  reason?: string | null
  operator?: string | null
  businessTime?: string | null
  processedAt?: string | null
  mappingVersion?: string | null
  summary?: string
}

export interface ChangeListData {
  page: number
  size: number
  total: number
  items: ChangeItem[]
}

export interface SourceStatus {
  sourceId: string
  sourceUp: boolean
  status: string
  statusLabel: string
  lastSuccessSyncAt: string | null
  demoTime: string | null
  snapshotRevision: number
  currentSourceRevision: number
  pendingCount: number
  processingCount: number
  errorCount: number
  deadCount: number
  freshness: string
  mappingVersion: string | null
  hint: string
}

export interface RevisionView {
  revision: number
  demoTime: string | null
  lastSuccessSyncAt: string | null
  pendingCount: number
  sourceUp: boolean
}

export interface OverviewData {
  counts: { objects: number; relations: number; objectTypes: number; relationTypes: number }
  dataSource: { sourceUp: boolean; status: string; lastSuccessSyncAt: string | null; pendingCount: number }
  sync: { revision: number; demoTime: string | null; errorCount: number; freshness: string }
  recentChanges: ChangeItem[]
  impactSummary: { selfChanged: number; derivedRelated: number }
}

export interface SyncTask {
  taskId: number
  type: string
  status: string
  snapshotRevision: number
  endRevision: number
  startedAt?: string | null
  finishedAt?: string | null
  operator?: string | null
}

export interface SyncTaskData {
  total: number
  page: number
  size: number
  items: SyncTask[]
}

export interface ModelRelationsData {
  mappingVersion: string
  relations: Record<string, unknown>[]
}

export interface ModelMappingsData {
  dataSources: Record<string, unknown>[]
  objectMappings: Record<string, unknown>[]
  versions: Record<string, unknown>[]
  providers: { key: string; impl: string }[]
  resolvers: { key: string; impl: string }[]
  registry: Record<string, string[]>
}

export interface OutboxItem {
  eventId: string
  batchId?: string | null
  changeKind: string
  revision: number
  status: string
  attempts: number
  lastError?: string | null
  businessTime?: string | null
  occurredAt?: string | null
  nextRetryAt?: string | null
}

export interface OutboxData {
  total: number
  items: OutboxItem[]
}

/** 把任意后端值安全地转成可展示文本（unknown 不直接参与模板运算）。 */
export function fmt(value: unknown): string {
  if (value === null || value === undefined) return '—'
  if (typeof value === 'string') return value
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  if (Array.isArray(value)) return value.map(fmt).join('、')
  try {
    return JSON.stringify(value)
  } catch {
    return String(value)
  }
}
