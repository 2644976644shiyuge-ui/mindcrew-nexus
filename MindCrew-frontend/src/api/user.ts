import request from '@/utils/request'

export interface LoginParams {
  username: string
  password: string
}

export interface RegisterParams {
  username: string
  password: string
  nickname?: string
  /** 邀请码（必填）：外部注册采用邀请码制度 */
  inviteCode: string
}

export interface InviteCode {
  id: number
  code: string
  maxUses?: number | null
  usedCount: number
  enabled: number
  expireTime?: string | null
  remark?: string | null
  createTime: string
}

export interface LoginResult {
  token: string
  userId: number
  username: string
  nickname: string
  avatar: string
  role: string
}

export interface UserInfo {
  id: number
  username: string
  nickname: string
  phone: string
  avatar: string
  role: string
  /** 任务 7 · 部门 ID */
  departmentId?: number | null
  /** 任务 7 · 职位 ID */
  positionId?: number | null
  /** #3 · 有效可用功能点（后端按 职位→部门→默认 解析，admin 为全部） */
  permissions?: string[]
  preference: string
  status: number
  /** 来源：register=外部注册 / admin=管理员创建（空按 admin 处理） */
  source?: string | null
  /** 账号到期时间，null=永久 */
  expireTime?: string | null
  createTime: string
  lastLogin: string
}

export const userApi = {
  login: (params: LoginParams): Promise<LoginResult> =>
    request.post('/user/login', params),

  register: (params: RegisterParams): Promise<void> =>
    request.post('/user/register', params),

  /** 退出登录 · 通知服务端吊销当前会话 */
  logout: (): Promise<void> =>
    request.post('/user/logout'),

  getUserInfo: (): Promise<UserInfo> =>
    request.get('/user/info'),

  updateUserInfo: (params: Partial<UserInfo>): Promise<void> =>
    request.put('/user/info', params),

  updatePreferenceProfile: (profile: string): Promise<void> =>
    request.put('/user/preference', profile, {
      headers: { 'Content-Type': 'text/plain' }
    }),

  listUsers: (params: { current: number; size: number; keyword?: string; role?: string; source?: string; status?: number }): Promise<any> =>
    request.get('/user/list', { params }),

  /** 设置账号到期时间（管理员）· expireTime 传 null 表示永久 */
  updateUserExpireTime: (userId: number, expireTime: string | null): Promise<void> =>
    request.put(`/user/${userId}/expire-time`, { expireTime }),

  /** 注册页公开获取邀请二维码 */
  getRegisterQr: (): Promise<{ qrUrl: string | null }> =>
    request.get('/user/register-qr'),

  /** 管理员创建用户 */
  createUser: (params: {
    username: string; password: string; nickname?: string;
    role?: string; departmentId?: number | null; positionId?: number | null
  }): Promise<number> =>
    request.post('/user', params),

  updateUserStatus: (userId: number, status: number): Promise<void> =>
    request.put(`/user/${userId}/status`, null, { params: { status } }),

  updateUserRole: (userId: number, role: string): Promise<void> =>
    request.put(`/user/${userId}/role`, null, { params: { role } }),

  /** 注销用户（管理员）· 逻辑删除 */
  deleteUser: (userId: number): Promise<void> =>
    request.delete(`/user/${userId}`),

  /** 任务 7 · 给用户分配部门 + 职位 */
  updateUserOrg: (userId: number, departmentId: number | null, positionId: number | null): Promise<void> =>
    request.put(`/user/${userId}/org`, { departmentId, positionId }),

  /** 管理员直接重置某用户密码 */
  adminResetPassword: (userId: number, newPassword: string): Promise<void> =>
    request.put(`/user/${userId}/reset-password`, { newPassword }),

  /** 查某用户的知识库授权配置（模式 + 直接授权的 collection id 列表） */
  getUserCollections: (userId: number): Promise<{ mode: string; collectionIds: number[] }> =>
    request.get(`/user/${userId}/collections`),

  /** 整体设置某用户的知识库授权（模式 inherit/override + 直接可访问的知识库） */
  setUserCollections: (userId: number, mode: string, collectionIds: number[]): Promise<void> =>
    request.put(`/user/${userId}/collections`, { mode, collectionIds }),

  uploadAvatar: (file: File): Promise<string> => {
    const form = new FormData()
    form.append('file', file)
    return request.post('/user/avatar', form, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
  },

  sendResetCode: (phone: string): Promise<void> =>
    request.post('/user/forgot-password/send-code', { phone }),

  resetPassword: (params: { phone: string; code: string; newPassword: string }): Promise<void> =>
    request.post('/user/forgot-password/reset', params),

  /** 登录态下修改密码（校验原密码） */
  changePassword: (params: { oldPassword: string; newPassword: string }): Promise<void> =>
    request.put('/user/password', params)
}

/** 邀请码 + 注册设置（管理员） */
export const inviteApi = {
  list: (): Promise<InviteCode[]> =>
    request.get('/admin/invite-codes'),

  generate: (params: { count?: number; maxUses?: number | null; expireTime?: string | null; remark?: string }): Promise<InviteCode[]> =>
    request.post('/admin/invite-codes/generate', params),

  setEnabled: (id: number, enabled: boolean): Promise<void> =>
    request.put(`/admin/invite-codes/${id}/enabled`, null, { params: { enabled } }),

  delete: (id: number): Promise<void> =>
    request.delete(`/admin/invite-codes/${id}`),

  /** 注册设置：二维码 URL + 默认有效期天数 */
  getSettings: (): Promise<{ qrUrl: string | null; defaultExpireDays: number }> =>
    request.get('/admin/register-settings'),

  updateSettings: (params: { defaultExpireDays?: number }): Promise<void> =>
    request.put('/admin/register-settings', params),

  uploadQr: (file: File): Promise<string> => {
    const form = new FormData()
    form.append('file', file)
    return request.post('/admin/register-qr', form, {
      headers: { 'Content-Type': 'multipart/form-data' },
    })
  },
}
