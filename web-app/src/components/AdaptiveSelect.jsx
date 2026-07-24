import { Children, isValidElement, useId, useRef, useState } from 'react'
import {
  Box,
  IconButton,
  List,
  ListItemButton,
  ListItemIcon,
  ListItemText,
  Stack,
  TextField,
  Typography,
  useMediaQuery,
} from '@mui/material'
import { useTheme } from '@mui/material/styles'
import { CheckIcon, ChevronDownIcon, CloseIcon } from '@/app/AppIcons'
import AnimatedBottomSheet from './AnimatedBottomSheet'

function textFromNode(node) {
  if (typeof node === 'string' || typeof node === 'number') return String(node)
  if (Array.isArray(node)) return node.map(textFromNode).join('')
  if (isValidElement(node)) return textFromNode(node.props.children)
  return ''
}

export default function AdaptiveSelect({
  children,
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
  const inputRef = useRef(null)
  const restoreFocusRef = useRef(false)
  const titleId = useId()
  const options = Children.toArray(children).filter(isValidElement)
  const selected = options.find((option) => String(option.props.value) === String(value))
  const selectedLabel = selected ? textFromNode(selected.props.children) : ''
  const accessibleLabel = label ?? textFieldProps['aria-label'] ?? name ?? 'Select option'

  if (!mobile) {
    return (
      <TextField
        {...textFieldProps}
        select
        name={name}
        label={label}
        value={value}
        onChange={onChange}
        slotProps={slotProps}
      >
        {children}
      </TextField>
    )
  }

  const openSheet = () => {
    if (!textFieldProps.disabled) setOpen(true)
  }

  const closeSheet = () => {
    restoreFocusRef.current = true
    setOpen(false)
  }

  const choose = (option) => {
    if (option.props.disabled) return
    onChange?.({ target: { value: option.props.value, name } }, option)
    closeSheet()
  }

  return (
    <>
      <TextField
        {...textFieldProps}
        name={name}
        label={label}
        value={selectedLabel}
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
            endAdornment: (
              <ChevronDownIcon
                aria-hidden="true"
                sx={{ color: textFieldProps.disabled ? 'text.disabled' : 'text.secondary' }}
              />
            ),
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
            'data-presentation': 'bottom-sheet-options',
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
            pb: 1,
          }}
        >
          <Typography id={titleId} variant="h6">{accessibleLabel}</Typography>
          <IconButton aria-label={`Close ${accessibleLabel}`} onClick={closeSheet}>
            <CloseIcon />
          </IconButton>
        </Stack>
        <Box sx={{ overflowY: 'auto', overscrollBehavior: 'contain' }}>
          <List role="listbox" disablePadding>
            {options.map((option) => {
              const optionSelected = String(option.props.value) === String(value)
              return (
                <ListItemButton
                  key={String(option.key ?? option.props.value)}
                  role="option"
                  selected={optionSelected}
                  aria-selected={optionSelected}
                  disabled={option.props.disabled}
                  onClick={() => choose(option)}
                  sx={[{ minHeight: 48, px: 2 }, option.props.sx]}
                >
                  <ListItemIcon sx={{ minWidth: 40 }}>
                    {optionSelected ? <CheckIcon color="primary" /> : null}
                  </ListItemIcon>
                  <ListItemText primary={option.props.children} />
                </ListItemButton>
              )
            })}
          </List>
        </Box>
      </AnimatedBottomSheet>
    </>
  )
}
