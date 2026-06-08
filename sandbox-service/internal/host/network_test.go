package host

import (
	"strings"
	"testing"
)

func TestNetworkPolicyRenderIncludesTapGuestAndAllowedCIDR(t *testing.T) {
	policy := NetworkPolicy{
		TapName:    "tap-agenthub-1",
		GuestCIDR:  "172.16.10.2/32",
		AllowCIDRs: []string{"10.0.0.0/8", "192.168.0.0/16"},
	}

	rules, err := policy.RenderNFTablesRules()
	if err != nil {
		t.Fatalf("RenderNFTablesRules() error = %v", err)
	}

	for _, expected := range []string{
		"tap-agenthub-1",
		"172.16.10.2/32",
		"10.0.0.0/8",
		"192.168.0.0/16",
		"policy drop",
		"accept",
	} {
		if !strings.Contains(rules, expected) {
			t.Fatalf("rules do not contain %q:\n%s", expected, rules)
		}
	}
}

func TestNetworkPolicyRenderDropsByDefaultWithoutAllowedCIDRs(t *testing.T) {
	policy := NetworkPolicy{
		TapName:   "tap-agenthub-2",
		GuestCIDR: "172.16.20.2/32",
	}

	rules, err := policy.RenderNFTablesRules()
	if err != nil {
		t.Fatalf("RenderNFTablesRules() error = %v", err)
	}

	if strings.Contains(rules, "iifname \"tap-agenthub-2\" ip saddr 172.16.20.2/32 ip daddr") {
		t.Fatalf("expected no allow CIDR rules:\n%s", rules)
	}
	if !strings.Contains(rules, "policy drop") {
		t.Fatalf("expected drop-by-default policy:\n%s", rules)
	}
}

func TestNetworkPolicyRejectsIPv6CIDR(t *testing.T) {
	policy := NetworkPolicy{
		TapName:    "tap-agenthub-3",
		GuestCIDR:  "172.16.30.2/32",
		AllowCIDRs: []string{"::1/128"},
	}

	_, err := policy.RenderNFTablesRules()
	if err == nil {
		t.Fatal("expected IPv6 error")
	}
	if !strings.Contains(err.Error(), "当前网络策略仅支持 IPv4 CIDR") {
		t.Fatalf("error = %q", err.Error())
	}
}

func TestNetworkPolicyRejectsInvalidTapName(t *testing.T) {
	policy := NetworkPolicy{
		TapName:   "tap-agenthub-1; flush ruleset",
		GuestCIDR: "172.16.30.2/32",
	}

	_, err := policy.RenderNFTablesRules()
	if err == nil {
		t.Fatal("expected tap name error")
	}
	if !strings.Contains(err.Error(), "tap 名称只能包含字母、数字、下划线和短横线") {
		t.Fatalf("error = %q", err.Error())
	}
}
