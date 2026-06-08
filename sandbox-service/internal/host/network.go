package host

import (
	"fmt"
	"strings"
)

type NetworkPolicy struct {
	TapName    string
	GuestCIDR  string
	AllowCIDRs []string
}

func (p NetworkPolicy) RenderNFTablesRules() string {
	var b strings.Builder
	fmt.Fprintf(&b, "table inet agenthub_sandbox_%s {\n", sanitizeNFTName(p.TapName))
	b.WriteString("  chain forward {\n")
	b.WriteString("    type filter hook forward priority 0; policy drop;\n")
	fmt.Fprintf(&b, "    oifname %q ip daddr %s ct state established,related accept\n", p.TapName, p.GuestCIDR)
	for _, cidr := range p.AllowCIDRs {
		fmt.Fprintf(&b, "    iifname %q ip saddr %s ip daddr %s accept\n", p.TapName, p.GuestCIDR, cidr)
	}
	b.WriteString("  }\n")
	b.WriteString("}\n")
	return b.String()
}

func sanitizeNFTName(name string) string {
	var b strings.Builder
	for _, r := range name {
		if r >= 'a' && r <= 'z' || r >= 'A' && r <= 'Z' || r >= '0' && r <= '9' || r == '_' {
			b.WriteRune(r)
			continue
		}
		b.WriteByte('_')
	}
	if b.Len() == 0 {
		return "default"
	}
	return b.String()
}
