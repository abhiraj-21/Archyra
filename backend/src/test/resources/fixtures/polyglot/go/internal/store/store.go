package store

import (
	"fmt"

	"github.com/acme/app/internal/model"
)

type Repo interface {
	Save(u *model.User) error
	Find(id string) (*model.User, error)
}

type Base struct{}

type Server struct {
	*Base
	repo Repo
	log  fmt.Stringer
}

func NewServer(repo Repo) *Server {
	s := &Server{repo: repo}
	fmt.Println("created")
	return s
}
