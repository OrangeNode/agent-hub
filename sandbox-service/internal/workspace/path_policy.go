package workspace

import (
	"errors"
	"path"
	"strings"
)

// PathPolicy 只校验 guest 可见路径的词法边界，不用于 host 文件系统防逃逸。
type PathPolicy struct {
	root string
}

const tempRoot = "/tmp"

func NewPathPolicy(root string) PathPolicy {
	return PathPolicy{root: strings.TrimRight(root, "/")}
}

func (p PathPolicy) Resolve(input string) (string, error) {
	return p.resolveWithDefault(input, p.root, false)
}

func (p PathPolicy) ResolveInput(input string) (string, error) {
	resolved, err := p.resolveWithDefault(input, p.root+"/input", true)
	if err != nil {
		return "", err
	}
	if resolved != p.root+"/input" && !strings.HasPrefix(resolved, p.root+"/input/") {
		return "", errors.New("上传文件只能写入 /workspace/input")
	}
	return resolved, nil
}

func (p PathPolicy) resolveWithDefault(input string, defaultDir string, rejectEmpty bool) (string, error) {
	trimmed := strings.TrimSpace(strings.ReplaceAll(input, "\\", "/"))
	if rejectEmpty && trimmed == "" {
		return "", errors.New("路径不能为空")
	}
	if isWindowsDrivePath(trimmed) || hasParentTraversal(trimmed) {
		return "", errors.New("路径不在允许访问范围内")
	}
	cleaned := path.Clean(trimmed)
	if cleaned == "." || cleaned == "/" {
		cleaned = p.root
	}
	if cleaned == "workspace" || strings.HasPrefix(cleaned, "workspace/") {
		cleaned = "/" + cleaned
	} else if strings.HasPrefix(cleaned, "input/") || cleaned == "input" ||
		strings.HasPrefix(cleaned, "artifacts/") || cleaned == "artifacts" ||
		strings.HasPrefix(cleaned, "output/") || cleaned == "output" {
		cleaned = p.root + "/" + cleaned
	} else if !strings.HasPrefix(cleaned, "/") {
		cleaned = defaultDir + "/" + cleaned
	}
	cleaned = path.Clean(cleaned)
	if cleaned == tempRoot || strings.HasPrefix(cleaned, tempRoot+"/") {
		return cleaned, nil
	}
	if !strings.HasPrefix(cleaned, p.root+"/") && cleaned != p.root {
		return "", errors.New("路径不在允许访问范围内")
	}
	return cleaned, nil
}

func hasParentTraversal(value string) bool {
	for _, part := range strings.Split(value, "/") {
		if part == ".." {
			return true
		}
	}
	return false
}

func isWindowsDrivePath(value string) bool {
	if len(value) < 2 || value[1] != ':' {
		return false
	}
	first := value[0]
	return (first >= 'a' && first <= 'z') || (first >= 'A' && first <= 'Z')
}
