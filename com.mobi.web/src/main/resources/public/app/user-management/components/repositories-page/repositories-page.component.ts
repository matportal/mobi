/*-
 * #%L
 * com.mobi.web
 * $Id:$
 * $HeadURL:$
 * %%
 * Copyright (C) 2016 - 2026 iNovex Information Systems, Inc.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 * #L%
 */
import { Component, OnInit } from '@angular/core';
import { UntypedFormBuilder, Validators } from '@angular/forms';
import { finalize } from 'rxjs/operators';

import { RepositoryCreateConfig } from '../../../shared/models/repositoryCreateConfig.interface';
import { Repository } from '../../../shared/models/repository.interface';
import { RepositoryManagerService } from '../../../shared/services/repositoryManager.service';
import { ToastService } from '../../../shared/services/toast.service';

interface RepositoryDisplay extends Repository {
  capacityPercentage?: number;
}

@Component({
  selector: 'app-repositories-page',
  templateUrl: './repositories-page.component.html',
  styleUrls: ['./repositories-page.component.scss']
})
export class RepositoriesPageComponent implements OnInit {
  private readonly protectedRepositoryIds = new Set(['system', 'prov', 'ontologyCache']);

  repositories: RepositoryDisplay[] = [];
  creating = false;
  deleting = new Set<string>();
  showCreateForm = false;
  createForm = this.fb.group({
    id: ['', [Validators.required, Validators.pattern(/^[A-Za-z0-9_.-]{1,64}$/)]],
    title: ['', [Validators.required]],
    type: ['sparql', [Validators.required]],
    endpointUrl: [''],
    updateEndpointUrl: [''],
    writable: [false],
    quadMode: [true],
    dataDir: [''],
    tripleIndexes: ['spoc,posc'],
    syncDelay: [0],
    serverUrl: ['']
  });

  constructor(private _rm: RepositoryManagerService, private _toast: ToastService, private fb: UntypedFormBuilder) {}
  
  /**
   * Initializes the list of repositories by fetching them from the {@link RepositoryManagerService}.
   */
  ngOnInit(): void {
    this.loadRepositories();
  }

  loadRepositories(): void {
    this._rm.getRepositories().subscribe(repos => {
      this.repositories = repos.map(repo => ({
        ...repo,
        capacityPercentage: this.getCapacityPercentage(repo)
      }));
    }, error => {
      this._toast.createErrorToast(`Failed to load repositories ${error}`);
    });
  }

  toggleCreateForm(): void {
    this.showCreateForm = !this.showCreateForm;
  }

  canDelete(repo: Repository): boolean {
    return !this.protectedRepositoryIds.has(repo.id);
  }

  createRepository(): void {
    if (this.createForm.invalid) {
      this.createForm.markAllAsTouched();
      return;
    }
    const type = this.createForm.controls.type.value;
    const id = this.createForm.controls.id.value.trim();
    const title = this.createForm.controls.title.value.trim();

    const payload: RepositoryCreateConfig = { id, title, type };
    if (type === 'sparql') {
      payload.endpointUrl = this.createForm.controls.endpointUrl.value.trim();
      const updateUrl = this.createForm.controls.updateEndpointUrl.value.trim();
      if (updateUrl) {
        payload.updateEndpointUrl = updateUrl;
      }
      payload.writable = this.createForm.controls.writable.value;
      payload.quadMode = this.createForm.controls.quadMode.value;
      if (!payload.endpointUrl) {
        this._toast.createErrorToast('SPARQL endpoint URL is required');
        return;
      }
    } else if (type === 'native') {
      payload.dataDir = this.createForm.controls.dataDir.value.trim();
      payload.tripleIndexes = this.createForm.controls.tripleIndexes.value.trim();
      if (!payload.dataDir) {
        this._toast.createErrorToast('Native data directory is required');
        return;
      }
    } else if (type === 'memory') {
      payload.dataDir = this.createForm.controls.dataDir.value.trim();
      payload.tripleIndexes = this.createForm.controls.tripleIndexes.value.trim();
      payload.syncDelay = Number(this.createForm.controls.syncDelay.value || 0);
    } else if (type === 'http') {
      payload.serverUrl = this.createForm.controls.serverUrl.value.trim();
      if (!payload.serverUrl) {
        this._toast.createErrorToast('HTTP server URL is required');
        return;
      }
    }

    this.creating = true;
    this._rm.createRepository(payload).pipe(finalize(() => this.creating = false)).subscribe({
      next: () => {
        this._toast.createSuccessToast('Repository created');
        this.showCreateForm = false;
        this.createForm.reset({
          id: '',
          title: '',
          type: 'sparql',
          endpointUrl: '',
          updateEndpointUrl: '',
          writable: false,
          quadMode: true,
          dataDir: '',
          tripleIndexes: 'spoc,posc',
          syncDelay: 0,
          serverUrl: ''
        });
        this.loadRepositories();
      },
      error: (error) => {
        this._toast.createErrorToast(`Failed to create repository ${error}`);
      }
    });
  }

  deleteRepository(repo: Repository): void {
    if (!this.canDelete(repo) || this.deleting.has(repo.id)) {
      return;
    }
    if (!confirm(`Delete repository "${repo.id}"?`)) {
      return;
    }
    this.deleting.add(repo.id);
    this._rm.deleteRepository(repo.id).pipe(finalize(() => this.deleting.delete(repo.id))).subscribe({
      next: () => {
        this._toast.createSuccessToast('Repository deleted');
        this.loadRepositories();
      },
      error: (error) => {
        this._toast.createErrorToast(`Failed to delete repository ${error}`);
      }
    });
  }

  /**
   * Determines the percentage of capacity used in a native repository (the only Repository type with capacity limits).
   * 
   * @param {Repository} repo The repository to evaluate.
   * @returns {number} The percentage of capacity used, or 0 if the repository has no limit or triple count is 
   *    undefined.
   */
  getCapacityPercentage(repo: Repository): number {
    if (!repo.limit || repo.tripleCount === undefined) {
      return 0;
    }
    return (repo.tripleCount / repo.limit) * 100;
  }
}
