package host

import (
	"fmt"
	"net/netip"
	"strings"
)

type NetworkPolicy struct {
	TapName    string
	GuestCIDR  string
	AllowCIDRs []string
}

func (p NetworkPolicy) RenderNFTablesRules() (string, error) {
	if err := p.Validate(); err != nil {
		return "", err
	}

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
	return b.String(), nil
}

func (p NetworkPolicy) Validate() error {
	if !isValidTapName(p.TapName) {
		return fmt.Errorf("tap 名称只能包含字母、数字、下划线和短横线")
	}
	if err := validateIPv4CIDR("guest CIDR", p.GuestCIDR); err != nil {
		return err
	}
	for _, cidr := range p.AllowCIDRs {
		if err := validateIPv4CIDR("allow CIDR", cidr); err != nil {
			return err
		}
	}
	return nil
}

func validateIPv4CIDR(label, value string) error {
	prefix, err := netip.ParsePrefix(value)
	if err != nil {
		return fmt.Errorf("%s 配置无效: %w", label, err)
	}
	if !prefix.Addr().Is4() {
		return fmt.Errorf("当前网络策略仅支持 IPv4 CIDR: %s", value)
	}
	return nil
}

func isValidTapName(name string) bool {
	if name == "" {
		return false
	}
	for _, r := range name {
		if r >= 'a' && r <= 'z' || r >= 'A' && r <= 'Z' || r >= '0' && r <= '9' || r == '_' || r == '-' {
			continue
		}
		return false
	}
	return true
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
