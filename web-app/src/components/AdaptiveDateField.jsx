import { useId, useMemo, useRef, useState } from 'react'
import {
  Box,
  Button,
  IconButton,
  Stack,
  TextField,
  Typography,
  useMediaQuery,
} from '@mui/material'
import { useTheme } from '@mui/material/styles'
import {
  CalendarIcon,
  ChevronLeftIcon,
  ChevronRightIcon,
  CloseIcon,
} from '@/app/AppIcons'
import AnimatedBottomSheet from './AnimatedBottomSheet'

const displayDateFormatter = new Intl.DateTimeFormat('en-GB', {
  day: 'numeric',
  month: 'short',
  year: 'numeric',
})

const monthFormatter = new Intl.DateTimeFormat('en', {
  month: 'long',
  year: 'numeric',
})

const fullDateFormatter = new Intl.DateTimeFormat('en-GB', {
  day: 'numeric',
  month: 'long',
  year: 'numeric',
})

const weekdays = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat']

function parseDateKey(value) {
  if (!value) return null
  const [year, month, day] = value.split('-').map(Number)
  if (!year || !month || !day) return null
  const parsed = new Date(year, month - 1, day)
  return Number.isNaN(parsed.getTime()) ? null : parsed
}

function dateKey(year, monthIndex, day) {
  return [
    year,
    String(monthIndex + 1).padStart(2, '0'),
    String(day).padStart(2, '0'),
  ].join('-')
}

function monthStart(date) {
  return new Date(date.getFullYear(), date.getMonth(), 1)
}

function calendarCells(month) {
  const leadingBlanks = month.getDay()
  const daysInMonth = new Date(month.getFullYear(), month.getMonth() + 1, 0).getDate()
  return [
    ...Array.from({ length: leadingBlanks }, (_, index) => ({ key: `blank-${index}` })),
    ...Array.from({ length: daysInMonth }, (_, index) => ({
      key: `day-${index + 1}`,
      day: index + 1,
    })),
  ]
}

export default function AdaptiveDateField({
  value,
  onChange,
  label,
  name,
  slotProps = {},
  ...textFieldProps
}) {
  const theme = useTheme()
  const mobile = useMediaQuery(theme.breakpoints.down('md'))
  const [open, setOpen] = useState(false)
  const [visibleMonth, setVisibleMonth] = useState(() => monthStart(parseDateKey(value) ?? new Date()))
  const inputRef = useRef(null)
  const restoreFocusRef = useRef(false)
  const titleId = useId()
  const monthId = useId()
  const selectedDate = parseDateKey(value)
  const cells = useMemo(() => calendarCells(visibleMonth), [visibleMonth])
  const accessibleLabel = label ?? textFieldProps['aria-label'] ?? name ?? 'Choose date'
  const displayValue = selectedDate ? displayDateFormatter.format(selectedDate) : ''

  if (!mobile) {
    return (
      <TextField
        {...textFieldProps}
        type="date"
        name={name}
        label={label}
        value={value}
        onChange={onChange}
        slotProps={{
          ...slotProps,
          inputLabel: {
            shrink: true,
            ...slotProps.inputLabel,
          },
        }}
      />
    )
  }

  const openSheet = () => {
    if (textFieldProps.disabled) return
    setVisibleMonth(monthStart(selectedDate ?? new Date()))
    setOpen(true)
  }

  const closeSheet = () => {
    restoreFocusRef.current = true
    setOpen(false)
  }

  const commit = (nextValue) => {
    onChange?.({ target: { value: nextValue, name } })
    closeSheet()
  }

  const changeMonth = (offset) => {
    setVisibleMonth((current) => new Date(current.getFullYear(), current.getMonth() + offset, 1))
  }

  return (
    <>
      <TextField
        {...textFieldProps}
        name={name}
        label={label}
        value={displayValue}
        placeholder="Choose date"
        onClick={openSheet}
        onKeyDown={(event) => {
          textFieldProps.onKeyDown?.(event)
          if (!textFieldProps.disabled && ['Enter', ' ', 'ArrowDown'].includes(event.key)) {
            event.preventDefault()
            openSheet()
          }
        }}
        inputRef={inputRef}
        sx={[{ '& .MuiInputBase-root': { minHeight: 48 } }, textFieldProps.sx]}
        slotProps={{
          ...slotProps,
          inputLabel: {
            shrink: true,
            ...slotProps.inputLabel,
          },
          htmlInput: {
            ...slotProps.htmlInput,
            readOnly: true,
            inputMode: 'none',
            role: 'combobox',
            'aria-haspopup': 'dialog',
            'aria-expanded': open,
            'aria-label': label ? undefined : accessibleLabel,
          },
          input: {
            ...slotProps.input,
            readOnly: true,
            endAdornment: <CalendarIcon aria-hidden="true" sx={{ color: 'text.secondary' }} />,
          },
        }}
      />

      <AnimatedBottomSheet
        open={open}
        onClose={closeSheet}
        slotProps={{
          transition: {
            onExited: () => {
              if (!restoreFocusRef.current) return
              restoreFocusRef.current = false
              inputRef.current?.focus()
            },
          },
          paper: {
            role: 'dialog',
            'aria-modal': 'true',
            'aria-labelledby': titleId,
            'aria-describedby': monthId,
            'data-presentation': 'bottom-sheet-date-picker',
            sx: {
              '--bottom-sheet-feature-max-height': '80dvh',
              pb: 'max(16px, env(safe-area-inset-bottom))',
            },
          },
        }}
      >
        <Stack
          direction="row"
          sx={{
            alignItems: 'center',
            justifyContent: 'space-between',
            flexShrink: 0,
            px: 2,
            pt: 2,
            pb: 0.5,
          }}
        >
          <Typography id={titleId} variant="h6">{accessibleLabel}</Typography>
          <IconButton aria-label={`Close ${accessibleLabel}`} onClick={closeSheet}>
            <CloseIcon />
          </IconButton>
        </Stack>

        <Box sx={{ overflowY: 'auto', overscrollBehavior: 'contain', px: 0.75, pb: 1 }}>
          <Stack
            direction="row"
            sx={{ alignItems: 'center', justifyContent: 'space-between', px: 0.75, pb: 1 }}
          >
            <IconButton aria-label="Previous month" onClick={() => changeMonth(-1)}>
              <ChevronLeftIcon />
            </IconButton>
            <Typography id={monthId} aria-live="polite" sx={{ fontWeight: 700 }}>
              {monthFormatter.format(visibleMonth)}
            </Typography>
            <IconButton aria-label="Next month" onClick={() => changeMonth(1)}>
              <ChevronRightIcon />
            </IconButton>
          </Stack>

          <Box
            role="grid"
            aria-labelledby={monthId}
            sx={{ display: 'grid', gridTemplateColumns: 'repeat(7, minmax(0, 1fr))' }}
          >
            {weekdays.map((weekday) => (
              <Typography
                key={weekday}
                role="columnheader"
                variant="body2"
                sx={{
                  py: 0.75,
                  color: weekday === 'Sun' || weekday === 'Sat' ? 'primary.main' : 'text.secondary',
                  fontSize: '0.75rem',
                  fontWeight: 700,
                  textAlign: 'center',
                }}
              >
                {weekday}
              </Typography>
            ))}
            {cells.map((cell) => {
              if (!cell.day) return <Box key={cell.key} role="gridcell" />

              const candidate = new Date(
                visibleMonth.getFullYear(),
                visibleMonth.getMonth(),
                cell.day,
              )
              const candidateKey = dateKey(
                visibleMonth.getFullYear(),
                visibleMonth.getMonth(),
                cell.day,
              )
              const selected = candidateKey === value

              return (
                <Box key={cell.key} role="gridcell" sx={{ display: 'grid', placeItems: 'center' }}>
                  <Button
                    aria-label={fullDateFormatter.format(candidate)}
                    aria-pressed={selected}
                    onClick={() => commit(candidateKey)}
                    sx={{
                      minWidth: 44,
                      width: 44,
                      height: 44,
                      p: 0,
                      borderRadius: '50%',
                      color: selected ? 'primary.contrastText' : 'text.primary',
                      bgcolor: selected ? 'primary.main' : 'transparent',
                      '&:hover': {
                        bgcolor: selected ? 'primary.dark' : 'highlight.main',
                      },
                    }}
                  >
                    {cell.day}
                  </Button>
                </Box>
              )
            })}
          </Box>

          <Box sx={{ px: 1.25, pt: 1 }}>
            <Button variant="text" disabled={!value} onClick={() => commit('')}>
              Clear date
            </Button>
          </Box>
        </Box>
      </AnimatedBottomSheet>
    </>
  )
}
