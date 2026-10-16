import type {
  ActionIngestionRequest,
  ActionIngestionResponse,
  ConnectorExecutionApiRequest,
  ConnectorExecutionApiResponse,
  PolicyEvaluationResponse,
  AuthCheckResponse,
  ApprovalTicketResponse,
  HealthResponse,
  ErrorResponse,
  Agent,
  AgentRegistrationRequest,
  AgentRegistrationResponse,
  AgentUpdateRequest,
  AgentApiKey,
  Resource,
  ResourceRegistrationRequest,
  Binding,
  BindingCreateRequest,
  BindingUpdateRequest,
  Policy,
  PolicyCreateRequest,
  PolicyVersionCreateRequest,
  AuditLog,
  User,
  PendingApprovalTicket,
  DashboardSummary,
  AuthorizedPerson,
} from '../types';

const API_BASE = '/v1';
const ACTUATOR_BASE = '/actuator';

class ApiError extends Error {
  constructor(
    public status: number,
    public body: ErrorResponse,
  ) {
    super(body.message || 'Request failed');
    this.name = 'ApiError';
  }
}

async function request<T>(url: string, options: RequestInit = {}): Promise<T> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...((options.headers as Record<string, string>) || {}),
  };

  // Human session auth is delivered via the HttpOnly session cookie, which the
  // browser attaches to this same-origin request automatically. No credential
  // header is injected from JS-accessible storage.

  // Send agent API key if available (agent auth)
  const apiKey = localStorage.getItem('netherite_api_key');
  if (apiKey) {
    headers['X-Netherite-Agent-Key'] = apiKey;
  }

  const res = await fetch(url, { ...options, headers });

  if (!res.ok) {
    if (res.status === 401) {
      localStorage.removeItem('netherite_profile');
      window.location.href = '/login';
      throw new ApiError(401, { error: 'ERR_UNAUTHORIZED', message: 'Session expired. Please sign in again.' });
    }
    let body: ErrorResponse;
    try {
      body = await res.json();
    } catch {
      body = { error: 'HTTP_ERROR', message: `Request failed with status ${res.status}` };
    }
    throw new ApiError(res.status, body);
  }

  if (res.status === 204) {
    return undefined as T;
  }

  return res.json();
}

// --- Auth ---
export async function authCheck(): Promise<AuthCheckResponse> {
  return request<AuthCheckResponse>(`${API_BASE}/actions/auth-check`);
}

// --- Actions ---
export async function ingestAction(data: ActionIngestionRequest): Promise<ActionIngestionResponse> {
  return request<ActionIngestionResponse>(`${API_BASE}/actions/ingest`, {
    method: 'POST',
    body: JSON.stringify(data),
  });
}

export async function executeAction(data: ConnectorExecutionApiRequest): Promise<ConnectorExecutionApiResponse> {
  return request<ConnectorExecutionApiResponse>(`${API_BASE}/actions/execute`, {
    method: 'POST',
    body: JSON.stringify(data),
  });
}

export async function evaluatePolicy(data: ActionIngestionRequest): Promise<PolicyEvaluationResponse> {
  return request<PolicyEvaluationResponse>(`${API_BASE}/actions/evaluate`, {
    method: 'POST',
    body: JSON.stringify(data),
  });
}

// --- Approvals ---
export async function getApprovalTicket(ticketId: string): Promise<ApprovalTicketResponse> {
  return request<ApprovalTicketResponse>(`${API_BASE}/approvals/${ticketId}`);
}

export async function approveTicket(
  ticketId: string,
  approverUserId?: string,
  notes?: string,
): Promise<ApprovalTicketResponse> {
  return request<ApprovalTicketResponse>(`${API_BASE}/approvals/${ticketId}/approve`, {
    method: 'POST',
    body: JSON.stringify({ approverUserId, notes }),
  });
}

export async function rejectTicket(
  ticketId: string,
  notes?: string,
): Promise<ApprovalTicketResponse> {
  return request<ApprovalTicketResponse>(`${API_BASE}/approvals/${ticketId}/reject`, {
    method: 'POST',
    body: JSON.stringify({ notes }),
  });
}

export async function listApprovals(params?: {
  status?: string;
  agentId?: string;
  resource?: string;
  actionType?: string;
}): Promise<PendingApprovalTicket[]> {
  const query = new URLSearchParams();
  if (params?.status) query.set('status', params.status);
  if (params?.agentId) query.set('agentId', params.agentId);
  if (params?.resource) query.set('resource', params.resource);
  if (params?.actionType) query.set('actionType', params.actionType);
  const qs = query.toString();
  return request<PendingApprovalTicket[]>(`${API_BASE}/approvals${qs ? '?' + qs : ''}`);
}

// --- Health / Actuator ---
export async function getHealth(): Promise<HealthResponse> {
  return request<HealthResponse>(`${ACTUATOR_BASE}/health`);
}

// --- Agents ---
export async function listAgents(): Promise<Agent[]> {
  return request<Agent[]>(`${API_BASE}/agents`);
}

export async function getAgent(agentId: string): Promise<Agent> {
  return request<Agent>(`${API_BASE}/agents/${agentId}`);
}

export async function createAgent(data: AgentRegistrationRequest): Promise<AgentRegistrationResponse> {
  return request<AgentRegistrationResponse>(`${API_BASE}/agents`, {
    method: 'POST',
    body: JSON.stringify(data),
  });
}

export async function updateAgent(agentId: string, data: AgentUpdateRequest): Promise<Agent> {
  return request<Agent>(`${API_BASE}/agents/${agentId}`, {
    method: 'PUT',
    body: JSON.stringify(data),
  });
}

export async function deactivateAgent(agentId: string): Promise<Agent> {
  return request<Agent>(`${API_BASE}/agents/${agentId}/deactivate`, { method: 'POST' });
}

export async function activateAgent(agentId: string): Promise<Agent> {
  return request<Agent>(`${API_BASE}/agents/${agentId}/activate`, { method: 'POST' });
}

export async function listAgentKeys(agentId: string): Promise<AgentApiKey[]> {
  return request<AgentApiKey[]>(`${API_BASE}/agents/${agentId}/keys`);
}

export async function createAgentKey(agentId: string, description?: string): Promise<{ keyId: string; keyIdentifier: string; secretToken: string; fullApiKey: string }> {
  return request(`${API_BASE}/agents/${agentId}/keys`, {
    method: 'POST',
    body: JSON.stringify({ description }),
  });
}

export async function revokeAgentKey(agentId: string, keyId: string): Promise<{ message: string }> {
  return request(`${API_BASE}/agents/${agentId}/keys/${keyId}/revoke`, { method: 'POST' });
}

// --- Resources ---
export async function listResources(): Promise<Resource[]> {
  return request<Resource[]>(`${API_BASE}/resources`);
}

export async function getResource(resourceId: string): Promise<Resource> {
  return request<Resource>(`${API_BASE}/resources/${resourceId}`);
}

export async function createResource(data: ResourceRegistrationRequest): Promise<Resource> {
  return request<Resource>(`${API_BASE}/resources`, {
    method: 'POST',
    body: JSON.stringify(data),
  });
}

export async function updateResource(resourceId: string, data: ResourceRegistrationRequest): Promise<Resource> {
  return request<Resource>(`${API_BASE}/resources/${resourceId}`, {
    method: 'PUT',
    body: JSON.stringify(data),
  });
}

export async function deactivateResource(resourceId: string): Promise<Resource> {
  return request<Resource>(`${API_BASE}/resources/${resourceId}/deactivate`, { method: 'POST' });
}

export async function activateResource(resourceId: string): Promise<Resource> {
  return request<Resource>(`${API_BASE}/resources/${resourceId}/activate`, { method: 'POST' });
}

// --- Bindings ---
export async function listBindings(): Promise<Binding[]> {
  return request<Binding[]>(`${API_BASE}/bindings`);
}

export async function getBinding(bindingId: string): Promise<Binding> {
  return request<Binding>(`${API_BASE}/bindings/${bindingId}`);
}

export async function createBinding(data: BindingCreateRequest): Promise<Binding> {
  return request<Binding>(`${API_BASE}/bindings`, {
    method: 'POST',
    body: JSON.stringify(data),
  });
}

export async function updateBinding(bindingId: string, data: BindingUpdateRequest): Promise<Binding> {
  return request<Binding>(`${API_BASE}/bindings/${bindingId}`, {
    method: 'PUT',
    body: JSON.stringify(data),
  });
}

export async function deleteBinding(bindingId: string): Promise<{ message: string }> {
  return request(`${API_BASE}/bindings/${bindingId}`, { method: 'DELETE' });
}

// --- Policies ---
export async function listPolicies(): Promise<Policy[]> {
  return request<Policy[]>(`${API_BASE}/policies`);
}

export async function getPolicy(policyId: string): Promise<Policy> {
  return request<Policy>(`${API_BASE}/policies/${policyId}`);
}

export async function createPolicy(data: PolicyCreateRequest): Promise<Policy> {
  return request<Policy>(`${API_BASE}/policies`, {
    method: 'POST',
    body: JSON.stringify(data),
  });
}

export async function updatePolicy(policyId: string, data: PolicyCreateRequest): Promise<Policy> {
  return request<Policy>(`${API_BASE}/policies/${policyId}`, {
    method: 'PUT',
    body: JSON.stringify(data),
  });
}

export async function createPolicyVersion(policyId: string, data: PolicyVersionCreateRequest): Promise<Policy> {
  return request<Policy>(`${API_BASE}/policies/${policyId}/versions`, {
    method: 'POST',
    body: JSON.stringify(data),
  });
}

export async function activatePolicyVersion(policyId: string, versionId: string): Promise<Policy> {
  return request<Policy>(`${API_BASE}/policies/${policyId}/versions/${versionId}/activate`, { method: 'POST' });
}

// --- Audit ---
export async function listAuditLogs(): Promise<AuditLog[]> {
  return request<AuditLog[]>(`${API_BASE}/audit`);
}

export async function getAuditLog(requestId: string): Promise<AuditLog> {
  return request<AuditLog>(`${API_BASE}/audit/${requestId}`);
}

// --- Users ---
export async function listUsers(): Promise<User[]> {
  return request<User[]>(`${API_BASE}/users`);
}

export async function getUser(userId: string): Promise<User> {
  return request<User>(`${API_BASE}/users/${userId}`);
}

// --- Dashboard ---
export async function getDashboard(): Promise<DashboardSummary> {
  return request<DashboardSummary>(`${API_BASE}/dashboard`);
}

export async function triggerHealthCheck(): Promise<{ status: string; message: string }> {
  return request<{ status: string; message: string }>(`${API_BASE}/dashboard/health/check`, {
    method: 'POST',
  });
}

// --- WS6: authorized person ---
export async function getAuthorizedPerson(orgId: string): Promise<AuthorizedPerson> {
  return request<AuthorizedPerson>(`${API_BASE}/control/organizations/${orgId}/authorized-person`);
}

export async function transferAuthorizedPerson(
  orgId: string,
  userId: string,
  expectedCurrentUserId: string,
): Promise<AuthorizedPerson> {
  return request<AuthorizedPerson>(`${API_BASE}/control/organizations/${orgId}/authorized-person`, {
    method: 'PUT',
    body: JSON.stringify({ userId, expectedCurrentUserId }),
  });
}
// --- Helpers ---
export function isApiError(error: unknown): error is ApiError {
  return error instanceof ApiError;
}

export function getErrorMessage(error: unknown): string {
  if (isApiError(error)) {
    return error.body.message || `Error ${error.status}`;
  }
  if (error instanceof Error) {
    return error.message;
  }
  return 'An unexpected error occurred';
}
