export type DecisionType = 'ALLOW' | 'DENY' | 'HUMAN_APPROVAL';
export type ApprovalStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'EXPIRED' | 'CANCELLED';
export type ConnectorType = 'HTTP_REST' | 'GRAPHQL' | 'MCP' | 'GRPC';
export type SensitivityLevel = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';

export interface ActionIngestionRequest {
  action: string;
  resource: string;
  parameters?: Record<string, unknown>;
  userContext?: Record<string, unknown>;
  rawPrompt?: string;
}

export interface DecisionExplanation {
  finalDecision: DecisionType;
  primaryReason: string;
  contributingPolicies: string[];
  ruleEvaluations: string[];
  resolutionRule: string;
}

export interface ActionIngestionResponse {
  actionId: string;
  decision: DecisionType;
  status: string;
  statusUrl?: string;
  executionResult?: Record<string, unknown>;
  explanation?: DecisionExplanation;
}

export interface ConnectorExecutionApiRequest {
  action: string;
  resource: string;
  parameters?: Record<string, unknown>;
}

export interface ConnectorExecutionApiResponse {
  executionId: string;
  successful: boolean;
  statusCode: number;
  responseBody?: Record<string, unknown>;
  executionDurationMillis: number;
  errorMessage?: string;
  resource: string;
  connectorType?: string;
}

export interface PolicyEvaluationResponse {
  actionId: string;
  decision: DecisionType;
  explanation: DecisionExplanation;
  evaluationDurationNanos: number;
  ruleCount: number;
}

export interface AuthCheckResponse {
  authenticated: boolean;
  organizationId: string;
  agentId: string;
  keyIdentifier: string;
  trustTier: string;
  mechanism: string;
}

export interface ApprovalTicketResponse {
  ticketId: string;
  organizationId: string;
  agentId: string;
  actionType: string;
  targetResource: string;
  status: ApprovalStatus;
  expiresAt: string;
  createdAt: string;
  approverUserId?: string;
  approverNotes?: string;
  resolvedAt?: string;
  executionResult?: string;
  executionError?: string;
}

export interface HealthComponent {
  status: string;
  details?: Record<string, unknown>;
}

export interface HealthResponse {
  status: string;
  components?: Record<string, HealthComponent>;
  groups?: string[];
}

export interface ErrorResponse {
  error: string;
  message: string;
  timestamp?: string;
  details?: string[];
}

export interface PendingApprovalTicket {
  ticketId: string;
  status: ApprovalStatus;
  actionType: string;
  targetResource: string;
  organizationId: string;
  agentId: string;
  expiresAt: string;
  createdAt: string;
}

export interface AuditEvent {
  id: string;
  organizationId: string;
  requestId: string;
  agentId?: string;
  actionType: string;
  targetResource: string;
  decision: string;
  executionDurationMs: number;
  createdAt: string;
}

// --- Management API Types ---

export interface Agent {
  id: string;
  organizationId: string;
  name: string;
  description?: string;
  trustTier?: string;
  status: string;
  createdAt: string;
}

export interface AgentRegistrationRequest {
  name: string;
  description?: string;
  trustTier?: string;
}

export interface AgentUpdateRequest {
  name?: string;
  description?: string;
  trustTier?: string;
}

export interface AgentRegistrationResponse extends Agent {
  secretToken?: string;
  keyIdentifier?: string;
  fullApiKey?: string;
}

export interface AgentApiKey {
  keyId: string;
  keyIdentifier: string;
  description: string;
  valid: boolean;
  createdAt: string;
}

export interface Resource {
  id: string;
  organizationId: string;
  name: string;
  connectorType: ConnectorType;
  baseUrl: string;
  description?: string;
  sensitivityLevel?: SensitivityLevel;
  status: string;
  createdAt: string;
}

export interface ResourceRegistrationRequest {
  name: string;
  connectorType: ConnectorType;
  baseUrl: string;
  description?: string;
  sensitivityLevel?: SensitivityLevel;
}

export interface Binding {
  id: string;
  organizationId: string;
  agentId: string;
  resourceId: string;
  allowedActions: string[];
  active: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface BindingCreateRequest {
  agentId: string;
  resourceId: string;
  allowedActions?: string[];
}

export interface BindingUpdateRequest {
  allowedActions?: string[];
  active?: boolean;
}

export interface Policy {
  id: string;
  organizationId: string;
  name: string;
  description?: string;
  status: string;
  activeVersionId?: string;
  versions: PolicyVersion[];
  createdAt: string;
  updatedAt: string;
}

export interface PolicyVersion {
  id: string;
  policyId: string;
  versionNumber: number;
  naturalLanguagePrompt?: string;
  regoCode: string;
  status: string;
  createdAt: string;
  activatedAt?: string;
}

export interface PolicyCreateRequest {
  name: string;
  description?: string;
}

export interface PolicyVersionCreateRequest {
  regoCode: string;
  naturalLanguagePrompt?: string;
}

export interface AuditLog {
  id: string;
  organizationId: string;
  requestId: string;
  agentId?: string;
  userId?: string;
  actionType: string;
  targetResource: string;
  decision: string;
  redactedParameters?: Record<string, unknown>;
  redactedExecutionResult?: Record<string, unknown>;
  executionDurationMs: number;
  currentRecordHash: string;
  createdAt: string;
}

export interface User {
  id: string;
  organizationId: string;
  email: string;
  fullName?: string;
  /** WS6: authority is an organization-level fact, not a per-user attribute. */
  authorizedPerson?: boolean;
  status: string;
  createdAt: string;
}

// --- WS6: the organization's single designated authorized person ---

export interface AuthorizedPerson {
  organizationId: string;
  /** null when the organization has nobody designated (fail-closed state). */
  userId: string | null;
  email: string | null;
  fullName: string | null;
  active: boolean;
  designatedAt: string | null;
}

// --- Dashboard Types ---

export interface DashboardOverview {
  agents: number;
  resources: number;
  pendingApprovals: number;
  actionsToday: number;
  deniedActions: number;
}

export interface DashboardHealthItem {
  status: 'HEALTHY' | 'PROBLEM' | 'UNKNOWN';
  description: string;
}

export interface DashboardResourceHealth {
  resourceId: string;
  resourceName: string;
  status: 'HEALTHY' | 'PROBLEM' | 'UNKNOWN';
  failureReason: string;
  lastCheckedAt: string | null;
  lastHealthyAt: string | null;
  lastFailureAt: string | null;
}

export interface DashboardHealth {
  agentConnection: DashboardHealthItem;
  resourceConnections: DashboardResourceHealth[];
  endToEndExecution: DashboardHealthItem;
  lastCheckedAt: string | null;
}

export interface DashboardActivity {
  id: string;
  actionType: string;
  targetResource: string;
  decision: string;
  eventType: string;
  agentId: string | null;
  executionDurationMs: number;
  createdAt: string | null;
}

export interface DashboardSummary {
  overview: DashboardOverview;
  health: DashboardHealth;
  recentActivity: DashboardActivity[];
}
