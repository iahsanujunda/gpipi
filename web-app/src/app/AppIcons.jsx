import { SvgIcon } from '@mui/material'

export function ReturnToSlackIcon(props) {
  return (
    <SvgIcon viewBox="0 0 24 24" {...props}>
      <path
        d="M11 5 4 12l7 7M5 12h15"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </SvgIcon>
  )
}

export function BrandIcon(props) {
  return (
    <SvgIcon viewBox="0 0 32 32" {...props}>
      <rect x="3" y="3" width="11" height="11" rx="3" fill="var(--brand-icon-accent)" />
      <rect x="18" y="3" width="11" height="11" rx="3" fill="currentColor" />
      <rect x="3" y="18" width="11" height="11" rx="3" fill="currentColor" />
      <rect x="18" y="18" width="11" height="11" rx="3" fill="var(--brand-icon-accent)" />
    </SvgIcon>
  )
}

export function CloseIcon(props) {
  return (
    <SvgIcon viewBox="0 0 24 24" {...props}>
      <path
        d="m5 5 14 14M19 5 5 19"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
      />
    </SvgIcon>
  )
}

export function BudgetsIcon(props) {
  return (
    <SvgIcon viewBox="0 0 24 24" {...props}>
      <rect x="3" y="7" width="18" height="13" rx="3" fill="none" stroke="currentColor" strokeWidth="1.8" />
      <path
        d="M7 7V4.5h10V7m-4 4v5"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.8"
        strokeLinecap="round"
      />
    </SvgIcon>
  )
}

export function ActivityIcon(props) {
  return (
    <SvgIcon viewBox="0 0 24 24" {...props}>
      <path
        d="M4 2.5h16v20l-4-3-4 3-4-3-4 3zM8 8h8m-8 5h6"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.8"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </SvgIcon>
  )
}
