export const STATUS = {
  ON_SALE: { label: '上架', type: 'success' },
  DRAFT: { label: '草稿', type: 'info' },
  OFF_SHELF: { label: '下架', type: 'warning' }
}

export const ORDER_STATUS = {
  PENDING_PAYMENT: { label: '待付款', type: 'warning' },
  PLACED: { label: '待处理', type: 'primary' },
  PAID: { label: '已付款', type: 'primary' },
  SHIPPED: { label: '已发货', type: 'success' },
  COMPLETED: { label: '已完成', type: 'info' },
  CANCELLED: { label: '已取消', type: 'danger' }
}

export function money(amount, currency = 'CNY') {
  if (amount === null || amount === undefined || amount === '') return ''
  const n = Number(amount).toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
  return (currency === 'USD' ? '$' : '¥') + n
}

export function dateTime(s) {
  if (!s) return ''
  return String(s).replace('T', ' ').slice(0, 16)
}

export const PLACEHOLDER = '/img/placeholder.svg'
