import request from '@/utils/http'

export interface Organization {
  id: string
  parentId: string | null
  code: string
  name: string
  remark: string | null
  sort: number
  root: boolean
  children: Organization[]
}

export type OrganizationInput = Pick<Organization, 'parentId' | 'code' | 'name' | 'remark' | 'sort'>

export const getOrganizations = () => request.get<Organization[]>({ url: '/api/organization/tree' })
export const getOrganizationOptions = (permission: string) =>
  request.get<Organization[]>({ url: '/api/organization/options', params: { permission } })
export const createOrganization = (data: OrganizationInput) =>
  request.post<string>({ url: '/api/organization', data })
export const updateOrganization = (id: string, data: OrganizationInput) =>
  request.put<boolean>({ url: `/api/organization/${id}`, data })
export const deleteOrganization = (id: string) =>
  request.del<boolean>({ url: `/api/organization/${id}` })
