package store

import "github.com/acme/app/internal/model"

func (s *Server) Start() error {
	u := model.NewUser("a")
	s.repo.Save(u)
	u.Display()
	helper(1, 2)
	return nil
}

func (Server) Stop() {}

func helper(a, b int) {}
