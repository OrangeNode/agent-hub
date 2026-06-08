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

	rules := policy.RenderNFTablesRules()

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

	rules := policy.RenderNFTablesRules()

	if strings.Contains(rules, "iifname \"tap-agenthub-2\" ip saddr 172.16.20.2/32 ip daddr") {
		t.Fatalf("expected no allow CIDR rules:\n%s", rules)
	}
	if !strings.Contains(rules, "policy drop") {
		t.Fatalf("expected drop-by-default policy:\n%s", rules)
	}
}
