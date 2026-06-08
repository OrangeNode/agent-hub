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

func NewPathPolicy(root string) PathPolicy {
	return PathPolicy{root: strings.TrimRight(root, "/")}
}

func (p PathPolicy) Resolve(input string) (string, error) {
	cleaned := path.Clean(strings.ReplaceAll(input, "\\", "/"))
	if cleaned == "." || cleaned == "/" {
		cleaned = p.root
	}
	if !strings.HasPrefix(cleaned, p.root+"/") && cleaned != p.root {
		return "", errors.New("路径不在允许访问范围内")
	}
	return cleaned, nil
}

func (p PathPolicy) ResolveInput(input string) (string, error) {
	resolved, err := p.Resolve(input)
	if err != nil {
		return "", err
	}
	if resolved != p.root+"/input" && !strings.HasPrefix(resolved, p.root+"/input/") {
		return "", errors.New("上传文件只能写入 /workspace/input")
	}
	return resolved, nil
}
