document.querySelectorAll('[data-filter]').forEach(button => button.addEventListener('click', () => {
  const wanted = button.dataset.filter;
  document.querySelectorAll('tbody tr[data-status]').forEach(row => {
    row.hidden = wanted !== 'ALL' && row.dataset.status !== wanted;
  });
}));
